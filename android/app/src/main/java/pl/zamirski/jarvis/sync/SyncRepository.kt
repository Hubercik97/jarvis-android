package pl.zamirski.jarvis.sync

/**
 * Miejsce na synchronizację telefonu z panelem Windows przez backend Jarvisa.
 *
 * Plan: backend dostaje endpointy /sync/{notes|tasks|reminders} (GET/PUT, ten sam token aplikacji),
 * dane w formacie LocalCodec, magazyn po stronie serwera (np. Cloudflare KV/D1).
 * Panel Windows i telefon czytają i zapisują te same zasoby.
 *
 * Dziś działa tylko NoSync — dane zostają na telefonie. UI pokazuje to jako „planowane”.
 */
interface SyncRepository {
    val isEnabled: Boolean
    suspend fun push(kind: String, json: String): Boolean
    suspend fun pull(kind: String): String?
}

object NoSync : SyncRepository {
    override val isEnabled = false
    override suspend fun push(kind: String, json: String) = false
    override suspend fun pull(kind: String): String? = null
}
