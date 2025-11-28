package org.coredns.container;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the assignment of list IDs to specific IP addresses in a cache,
 * which is persisted to a file using JSON format. Includes ListType (BLOCK/WHITE) support.
 */
public class ListAssignmentCache {
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private static class CacheData {
        public Map<String, Set<String>> assignments;
        public Map<String, ListType> listTypes;

        public CacheData(Map<String, Set<String>> assignments, Map<String, ListType> listTypes) {
            this.assignments = assignments;
            this.listTypes = listTypes;
        }
    }

    // Map: List ID -> Set of assigned IPs
    final Map<String, Set<String>> assignments;
    // Map: List ID -> ListType (BLOCK/WHITE)
    private final Map<String, ListType> listTypes;

    // Cache File Path
    private final String cacheFilePath;

    public ListAssignmentCache(String cacheFilePath) {
        this.cacheFilePath = cacheFilePath;

        CacheData loadedData = loadCacheFromFile();

        // Correct assignment using the loaded CacheData object
        this.assignments = loadedData.assignments;
        this.listTypes = loadedData.listTypes;
    }

    /**
     * Loads the cache from file into RAM.
     *
     * @return Loaded CacheData Object.
     */
    private CacheData loadCacheFromFile() {
        File cacheFile = new File(cacheFilePath);

        // Case 1: File exists, try loading
        if (cacheFile.exists()) {
            try (FileReader reader = new FileReader(cacheFile)) {
                System.out.println("Loading List Assignment Cache from file: " + cacheFilePath);

                Type cacheDataType = new TypeToken<CacheData>(){}.getType();
                CacheData data = gson.fromJson(reader, cacheDataType);

                if (data != null && data.assignments != null && data.listTypes != null) {
                    // Successfully loaded file.
                    return data;
                } else {
                    System.err.println("Cache file found, but data format is invalid. Starting fresh.");
                }
            } catch (IOException e) {
                System.err.println("WARNING: Failed to load List Assignment Cache due to error. Starting fresh.");
                e.printStackTrace();
            }
        }
        System.out.println("Cache file not found or load failed. Initializing new cache.");
        // Use ConcurrentHashMap for thread safety
        return new CacheData(new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
    }


    /**
     * Saves the current state of the cache (both maps wrapped in CacheData) to file.
     */
    public synchronized void saveCacheToFile() {
        try (FileWriter writer = new FileWriter(cacheFilePath)) {
            CacheData dataToSave = new CacheData(this.assignments, this.listTypes);
            gson.toJson(dataToSave, writer);
            System.out.println("List Assignment Cache saved successfully to: " + cacheFilePath);
        } catch (IOException e) {
            System.err.println("Error saving List Assignment Cache: " + e.getMessage());
        }
    }

    /**
     * Reassigns a list to an IP with a specific type, or unassigns it using ListType.NONE.
     * This method replaces assignList and unassignList.
     *
     * @param listId The ID of the list.
     * @param ip     The IP address.
     * @param type   The ListType (BLOCK, WHITE, or NONE to unassign).
     * @return true if the assignment state changed, false otherwise.
     */
    public synchronized boolean reassignList(String listId, String ip, ListType type) {
        String key = listId.trim();
        String val = ip.trim();
        boolean changed = false;

        // 1. Manage List Type (listTypes Map)
        if (type != ListType.NONE) {
            // Set the type, only if it changes
            if (!type.equals(listTypes.put(key, type))) {
                changed = true;
            }
        } 
        
        // 2. Manage IP Assignment (assignments Map)
        
        // Ensure the Set<String> exists for assignment tracking
        Set<String> ips = assignments.computeIfAbsent(key, k -> new HashSet<>());

        if (type == ListType.NONE) {
            // Remove the assignment
            if (ips.remove(val)) {
                changed = true;
                System.out.println("Unassigned list '" + listId + "' from IP " + ip + " (Type: NONE).");
            }
        } else {
            // Add the assignment
            if (ips.add(val)) {
                changed = true;
                System.out.println("Assigned list '" + listId + "' to IP " + ip + " as " + type + ".");
            }
        }

        // 3. Cleanup Assignment (if set becomes empty after removal)
        if (ips.isEmpty()) {
            // Remove the assignment entry if no IPs are left.
            if (assignments.remove(key) != null) {
                 changed = true;
            }
            // Also remove the type if the list is completely unassigned from all IPs.
            if (listTypes.remove(key) != null) {
                changed = true;
            }
        }
        
        if (changed) {
            saveCacheToFile();
        }
        return changed;
    }

    /**
     * Returns the assigned ListType for a list ID. Returns null if the list is unknown.
     *
     * @param listId The ID of the list.
     * @return The ListType (BLOCK or WHITE) or null if not found.
     */
    public ListType getListType(String listId) {
        return listTypes.get(listId.trim());
    }
    
    /**
     * Checks if a list is assigned to at least one IP address AND has a type (BLOCK/WHITE).
     * Required for the cleanupUnassignedDomains() logic.
     *
     * @param listId The ID of the list.
     * @return true if the list is active (assigned to an IP and has a type), false otherwise.
     */
    public boolean isAssignedToAnyIP(String listId) {
        String key = listId.trim();
        // Check 1: Is a type defined AND is it BLOCK or WHITE?
        ListType type = listTypes.get(key);
        if (type == null || type == ListType.NONE) {
            return false;
        }

        // Check 2: Is it assigned to at least one IP?
        Set<String> ips = assignments.get(key);
        return ips != null && !ips.isEmpty();
    }
    
    // Obsolete methods assignList, unassignList, isAssigned wurden entfernt.

    /**
     * Checks if a specific list is assigned to a specific IP address.
     *
     * @param listId The ID of the list.
     * @param ip     The IP address to check.
     * @return true if the list is assigned to the IP, false otherwise.
     */
    public boolean isAssignedToIP(String listId, String ip) {
        String key = listId.trim();
        String val = ip.trim();
        Set<String> ips = assignments.get(key);
        return ips != null && ips.contains(val);
}
}
