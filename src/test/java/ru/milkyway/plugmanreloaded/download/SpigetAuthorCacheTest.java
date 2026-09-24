package ru.milkyway.plugmanreloaded.download;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SpigetAuthorCacheTest {

    @Test
    void authorNameLookupFailureIsNeverCachedPermanently() throws Exception {
        PluginSearch search = new PluginSearch(null, null, null);
        Method resolveSpigetAuthor = PluginSearch.class.getDeclaredMethod("resolveSpigetAuthor", JsonObject.class);
        resolveSpigetAuthor.setAccessible(true);

        Field cacheField = PluginSearch.class.getDeclaredField("SPIGET_AUTHORS");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, String> cache = (Map<Integer, String>) cacheField.get(null);

        int dummyAuthorId = 999999999;
        cache.remove(dummyAuthorId);

        JsonObject authorObj = new JsonObject();
        authorObj.addProperty("id", dummyAuthorId);
        JsonObject root = new JsonObject();
        root.add("author", authorObj);

        String result = (String) resolveSpigetAuthor.invoke(search, root);
        assertEquals("SpigotMC", result);
        assertFalse(cache.containsKey(dummyAuthorId));
    }
}
