package app.n_zik.android.core.network

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.net.Proxy

class NetworkClientFactoryTest {

    @Test
    fun testKtorClientSingleton() {
        // Initial setup
        val client1 = NetworkClientFactory.getKtorClient(cacheless = true)
        val client2 = NetworkClientFactory.getKtorClient(cacheless = true)
        
        assertSame(client1, client2, "Ktor cacheless client should be a singleton")
        
        val cachedClient1 = NetworkClientFactory.getKtorClient(cacheless = false)
        val cachedClient2 = NetworkClientFactory.getKtorClient(cacheless = false)
        
        assertSame(cachedClient1, cachedClient2, "Ktor cached client should be a singleton")
        assertNotSame(client1, cachedClient1, "Cacheless and cached clients should be different instances")
    }

    @Test
    fun testConfigureResetsKtorClients() {
        val clientBefore = NetworkClientFactory.getKtorClient(cacheless = true)
        
        // Reconfigure
        NetworkClientFactory.configure(proxy = Proxy.NO_PROXY, cacheDir = null)
        
        val clientAfter = NetworkClientFactory.getKtorClient(cacheless = true)
        
        assertNotSame(clientBefore, clientAfter, "Ktor client should be reset after configuration change")
    }

    @Test
    fun testGetCachelessClient() {
        val client = NetworkClientFactory.getClient()
        val cachelessClient = NetworkClientFactory.getCachelessClient()
        
        assertNotSame(client, cachelessClient, "Cacheless OkHttpClient should be a new instance via newBuilder")
        assertNull(cachelessClient.cache, "Cacheless client should have null cache")
    }
}
