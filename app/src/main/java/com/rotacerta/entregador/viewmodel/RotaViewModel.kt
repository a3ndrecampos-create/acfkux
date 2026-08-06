package com.rotacerta.entregador.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rotacerta.entregador.RotaCertaApp
import com.rotacerta.entregador.data.*
import com.rotacerta.entregador.domain.GeocodingService
import com.rotacerta.entregador.domain.LatLng
import com.rotacerta.entregador.domain.RouteOptimizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ─── Estado do preview do pedido escaneado ────────────────────────────────────

/**
 * Dados que o scanner montou e o usuário precisa confirmar antes de a entrega
 * ser persistida. [isGeocoding] fica true enquanto a consulta ao ViaCEP /
 * Nominatim está em andamento — os botões de confirmação ficam desabilitados.
 */
data class PedidoPreview(
    val cep: String,
    val numero: String?,
    val trackingCode: String?,
    val address: String = "",
    val isGeocoding: Boolean = true,
    val approxLocation: Boolean = false,
    val geocodeError: String? = null,
    // guardado internamente para persistir se o usuário confirmar
    val lat: Double? = null,
    val lng: Double? = null,
    val cepData: com.rotacerta.entregador.network.CepResponse? = null
)

// ─── Resultado do scan de rastreio (busca pacote já existente na rota) ─────────

sealed class ScanLabelResult {
    data class Found(val position: Int, val total: Int, val address: String, val ambiguous: Boolean, val numero: String?) : ScanLabelResult()
    data class NotFound(val code: String) : ScanLabelResult()
}

class RotaViewModel(app: Application) : AndroidViewModel(app) {
    private val rotaCertaApp = app as RotaCertaApp
    private val db = rotaCertaApp.database
    private val configRepo = rotaCertaApp.configRepository
    private val deliveryDao = db.deliveryDao()
    private val historyDao = db.historyDao()

    val deliveries: StateFlow<List<Delivery>> =
        deliveryDao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val history: StateFlow<List<HistoryEntry>> =
        historyDao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val config: StateFlow<AppConfig> =
        configRepo.configFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppConfig())

    // Preview do pedido escaneado (null = nenhum em andamento)
    private val _pedidoPreview = MutableStateFlow<PedidoPreview?>(null)
    val pedidoPreview: StateFlow<PedidoPreview?> = _pedidoPreview

    // Resultado do scan de rastreio (verificar pacote já na rota)
    private val _scanLabelResult = MutableStateFlow<ScanLabelResult?>(null)
    val scanLabelResult: StateFlow<ScanLabelResult?> = _scanLabelResult

    fun clearScanLabelResult() { _scanLabelResult.value = null }

    private val _toast = MutableSharedFlow<String>()
    val toast: SharedFlow<String> = _toast

    // ─── CRUD básico ────────────────────────────────────────────────────────

    fun markDelivered(delivery: Delivery) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            deliveryDao.update(delivery.copy(status = DeliveryStatus.ENTREGUE, deliveredAt = now))
            historyDao.insert(HistoryEntry(originalDeliveryId = delivery.id, address = delivery.address, value = delivery.value, deliveredAt = now))
            _toast.emit("Entrega confirmada ✔")
        }
    }

    fun removeDelivery(delivery: Delivery) {
        viewModelScope.launch { deliveryDao.delete(delivery) }
    }

    fun clearAllDeliveries() {
        viewModelScope.launch { deliveryDao.clearAll() }
    }

    fun resetHistory() {
        viewModelScope.launch {
            historyDao.clearAll()
            _toast.emit("Histórico apagado")
        }
    }

    // ─── Consulta de CEP (usada pelo ConfigScreen para salvar destinos) ──────

    suspend fun lookupCep(cep: String): com.rotacerta.entregador.network.CepResponse =
        GeocodingService.lookupCep(cep)

    // ─── Fluxo "Escanear pedido" ─────────────────────────────────────────────

    /**
     * Chamado pelo [ScanPedidoDialog] assim que o OCR lê o CEP com confirmação.
     * Inicia geocodificação em background e publica o [PedidoPreview] com
     * [isGeocoding]=true enquanto aguarda — a UI mostra um spinner e bloqueia
     * o botão "Confirmar".
     */
    fun preparePedidoPreview(cep: String, numero: String?, trackingCode: String?) {
        // Estado inicial: geocodificando
        _pedidoPreview.value = PedidoPreview(
            cep = cep,
            numero = numero,
            trackingCode = trackingCode,
            isGeocoding = true
        )

        viewModelScope.launch {
            try {
                // 1. Consulta o CEP para pegar logradouro, bairro, cidade, UF
                val cepData = withContext(Dispatchers.IO) {
                    GeocodingService.lookupCep(cep)
                }

                // 2. Monta o endereço completo
                val street = cepData.logradouro.orEmpty() + if (!numero.isNullOrBlank()) ", $numero" else ""
                val address = listOfNotNull(
                    street.ifBlank { null },
                    cepData.bairro?.ifBlank { null },
                    cepData.localidade?.let { "$it - ${cepData.uf}" },
                    cep
                ).filter { it.isNotBlank() }.joinToString(", ")

                // 3. Geocodifica (lat/lng)
                val geo = withContext(Dispatchers.IO) {
                    GeocodingService.geocode(address, cepData, numero ?: "")
                }

                _pedidoPreview.value = PedidoPreview(
                    cep = cep,
                    numero = numero,
                    trackingCode = trackingCode,
                    address = address,
                    isGeocoding = false,
                    approxLocation = geo.approx,
                    lat = geo.lat,
                    lng = geo.lng,
                    cepData = cepData
                )
            } catch (e: Exception) {
                _pedidoPreview.value = _pedidoPreview.value?.copy(
                    isGeocoding = false,
                    geocodeError = e.message ?: "Não foi possível localizar o endereço"
                )
            }
        }
    }

    /** Usuário confirmou o preview → persiste a entrega e fecha o fluxo. */
    fun confirmPedido() {
        val p = _pedidoPreview.value ?: return
        if (p.isGeocoding || p.geocodeError != null || p.lat == null || p.lng == null) return

        viewModelScope.launch {
            deliveryDao.insert(
                Delivery(
                    address = p.address,
                    lat = p.lat,
                    lng = p.lng,
                    priority = Priority.MEDIA,
                    deadline = "",
                    value = config.value.defaultValue,
                    approxLocation = p.approxLocation,
                    trackingCode = p.trackingCode ?: ""
                )
            )
            _toast.emit(if (p.approxLocation) "Pedido adicionado (localização aproximada)" else "Pedido adicionado à rota ✔")
            _pedidoPreview.value = null
        }
    }

    /** Usuário quer escanear de novo — descarta o preview sem fechar o dialog. */
    fun cancelPedidoPreview() {
        _pedidoPreview.value = null
    }

    // ─── Scanner de rastreio (verificar pacote já na rota) ───────────────────

    fun scanPackageByTrackingCode(code: String) {
        viewModelScope.launch {
            val clean = code.trim()
            if (clean.isBlank()) return@launch

            val pendentes = deliveries.value.filter { it.status == DeliveryStatus.PENDENTE }.sortedBy { it.order }
            val match = deliveryDao.findByTrackingCode(clean)
                ?: pendentes.firstOrNull {
                    it.trackingCode.isNotBlank() &&
                    (it.trackingCode == clean || clean.contains(it.trackingCode) || it.trackingCode.contains(clean))
                }

            if (match == null || match.status != DeliveryStatus.PENDENTE) {
                _scanLabelResult.value = ScanLabelResult.NotFound(clean)
                return@launch
            }

            val totalParadas = pendentes.map { it.order }.distinct().size
            val pacotesNestaParada = pendentes.count { it.order == match.order }
            deliveryDao.markVerified(match.id)
            _scanLabelResult.value = ScanLabelResult.Found(
                position = match.order, total = totalParadas, address = match.address,
                ambiguous = pacotesNestaParada > 1, numero = null
            )
        }
    }

    // ─── Otimização de rota ──────────────────────────────────────────────────

    fun optimizeRoute() {
        viewModelScope.launch {
            val pending = deliveries.value.filter { it.status == DeliveryStatus.PENDENTE }
            if (pending.size < 2) {
                _toast.emit("Adicione ao menos 2 pedidos pendentes para otimizar")
                return@launch
            }
            val cfg = config.value
            val origin = cfg.originLat?.let { lat -> cfg.originLng?.let { lng -> LatLng(lat, lng) } }
            val returnPoint = cfg.homeLat?.let { lat -> cfg.homeLng?.let { lng -> LatLng(lat, lng) } } ?: origin
            val optimized = RouteOptimizer.optimize(pending, origin, cfg.sortDirection, cfg.roundTrip, returnPoint)
            deliveryDao.updateAll(optimized)
            _toast.emit("Rota otimizada! ${optimized.size} paradas reordenadas.")
        }
    }

    fun routeStats(): RouteOptimizer.RouteStats {
        val cfg = config.value
        val origin = cfg.originLat?.let { lat -> cfg.originLng?.let { lng -> LatLng(lat, lng) } }
        val returnPoint = cfg.homeLat?.let { lat -> cfg.homeLng?.let { lng -> LatLng(lat, lng) } } ?: origin
        val pending = deliveries.value.filter { it.status == DeliveryStatus.PENDENTE }
        return RouteOptimizer.computeStats(pending, origin, cfg.vehicle.avgSpeedKmh, cfg.roundTrip, returnPoint)
    }

    // ─── Config ──────────────────────────────────────────────────────────────

    fun updateConfig(update: (AppConfig) -> AppConfig) {
        viewModelScope.launch { configRepo.update(update) }
    }

    fun setOrigin(address: String) {
        viewModelScope.launch {
            if (address.isBlank()) {
                updateConfig { it.copy(originAddress = "", originLat = null, originLng = null) }
                return@launch
            }
            try {
                val geo = GeocodingService.geocode(address)
                updateConfig { it.copy(originAddress = address, originLat = geo.lat, originLng = geo.lng) }
                _toast.emit("Ponto de partida definido")
            } catch (e: Exception) {
                _toast.emit("Endereço de partida não encontrado")
            }
        }
    }

    fun setOriginFromGps() {
        viewModelScope.launch {
            try {
                val location = withContext(Dispatchers.IO) {
                    com.rotacerta.entregador.domain.GpsLocationProvider.getCurrentLocation(getApplication())
                }
                updateConfig {
                    it.copy(
                        originAddress = "Minha localização atual (GPS)",
                        originLat = location.latitude,
                        originLng = location.longitude
                    )
                }
                _toast.emit("Ponto de partida definido pelo GPS")
            } catch (e: SecurityException) {
                _toast.emit("Permita o acesso à localização para usar o GPS")
            } catch (e: Exception) {
                _toast.emit(e.message ?: "Não foi possível obter sua localização")
            }
        }
    }

    fun setHomeFromGps() {
        viewModelScope.launch {
            try {
                val location = withContext(Dispatchers.IO) {
                    com.rotacerta.entregador.domain.GpsLocationProvider.getCurrentLocation(getApplication())
                }
                updateConfig {
                    it.copy(
                        homeAddress = "Minha localização atual (GPS)",
                        homeLat = location.latitude,
                        homeLng = location.longitude
                    )
                }
                _toast.emit("Destino final definido pelo GPS")
            } catch (e: SecurityException) {
                _toast.emit("Permita o acesso à localização para usar o GPS")
            } catch (e: Exception) {
                _toast.emit(e.message ?: "Não foi possível obter sua localização")
            }
        }
    }

    // ─── Destinos salvos ─────────────────────────────────────────────────────

    fun addSavedDestination(label: String, cepData: com.rotacerta.entregador.network.CepResponse, numero: String) {
        viewModelScope.launch {
            val current = config.value.savedDestinations
            if (current.size >= AppConfig.MAX_SAVED_DESTINATIONS) {
                _toast.emit("Você já tem ${AppConfig.MAX_SAVED_DESTINATIONS} destinos salvos. Remova um antes de adicionar outro.")
                return@launch
            }
            val nomeLabel = label.ifBlank { "Destino ${current.size + 1}" }
            val street = cepData.logradouro.orEmpty() + if (numero.isNotBlank()) ", $numero" else ""
            val enderecoCompleto = listOfNotNull(street, cepData.bairro, cepData.localidade?.let { "$it - ${cepData.uf}" }, cepData.cep)
                .filter { it.isNotBlank() }.joinToString(", ")
            try {
                val geo = GeocodingService.geocode(enderecoCompleto, cepData, numero)
                val novo = SavedDestination(nomeLabel, enderecoCompleto, geo.lat, geo.lng)
                updateConfig { cfg ->
                    val ficaSelecionado = cfg.savedDestinations.isEmpty()
                    cfg.copy(
                        savedDestinations = cfg.savedDestinations + novo,
                        homeAddress = if (ficaSelecionado) novo.address else cfg.homeAddress,
                        homeLat = if (ficaSelecionado) novo.lat else cfg.homeLat,
                        homeLng = if (ficaSelecionado) novo.lng else cfg.homeLng
                    )
                }
                _toast.emit("Destino \"$nomeLabel\" salvo")
            } catch (e: Exception) {
                _toast.emit(e.message ?: "Não foi possível localizar esse endereço")
            }
        }
    }

    fun selectSavedDestination(dest: SavedDestination) {
        updateConfig { it.copy(homeAddress = dest.address, homeLat = dest.lat, homeLng = dest.lng) }
    }

    fun removeSavedDestination(dest: SavedDestination) {
        updateConfig { cfg ->
            val restante = cfg.savedDestinations.filter { it != dest }
            if (cfg.homeAddress == dest.address && cfg.homeLat == dest.lat && cfg.homeLng == dest.lng) {
                cfg.copy(savedDestinations = restante, homeAddress = "", homeLat = null, homeLng = null)
            } else {
                cfg.copy(savedDestinations = restante)
            }
        }
    }
}
