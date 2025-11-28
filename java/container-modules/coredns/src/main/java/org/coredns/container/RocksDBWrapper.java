package org.coredns.container;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Pattern;

import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

public class RocksDBWrapper {
    private static final int BATCH_SIZE = 10000;
    // Improved pattern for common domain/wildcard formats (A-Z, 0-9, ., *, !, -)
    private static final Pattern DOMAIN_PATTERN = Pattern.compile("^[a-zA-Z0-9.*!-]+(\\.[a-zA-Z0-9.*!-]+)*$");

    private final ListAssignmentCache assignmentCache;
    private final static String CACHE_FILE_NAME = "list_assignments.cache";

    private final String dbPath;
    private final RocksDB db;

    static {
        // Load the RocksDB native library once
        RocksDB.loadLibrary();
    }

    public RocksDBWrapper(String path) throws RocksDBException {
        this.dbPath = path;
        
        // Use try-with-resources for Options (though only one is opened here)
        try (@SuppressWarnings("resource")
        Options options = new Options().setCreateIfMissing(true)) {
            this.db = RocksDB.open(options, dbPath);
        }

        // Initialize the cache and load it from file in the same directory as the DB
        String cachePath = new File(dbPath).getParent() + File.separator + CACHE_FILE_NAME;
        this.assignmentCache = new ListAssignmentCache(cachePath);
    }

    /**
     * Imports domains line-by-line from a stream into RocksDB and SIMULTANEOUSLY
     * assigns the list to an IP with the specified ListType.
     * This is used by the /api/list/upload endpoint.
     * Synchronized to prevent race conditions with cleanupUnassignedDomains.
     *
     * @param listId The ID of the list.
     * @param stream The InputStream of the .txt file.
     * @param ip     The IP address to assign the list to.
     * @param type   The ListType (BLOCK or WHITE).
     * @return The number of imported domains.
     */
    public synchronized int importListFromStreamAndAssign(String listId, InputStream stream, String ip, ListType type)
            throws IOException, RocksDBException {

        // 1. Import domains from the stream and write them to RocksDB.
        int importedCount = importListFromStream(listId, stream);

        // 2. Assign the list to the IP and set its type if domains were imported.
        if (importedCount > 0) {
            // Use the central reassignList method
            assignmentCache.reassignList(listId, ip, type);
        }

        return importedCount;
    }

    /**
     * Executes a cleanup by removing domains from RocksDB whose associated list IDs 
     * are NO LONGER active (assigned to ANY IP with type BLOCK/WHITE).
     * This is an expensive, resource-intensive operation.
     */
    public synchronized void cleanupUnassignedDomains() throws RocksDBException {
        System.out.println("Starting cleanup of unassigned domains...");
        int deletedCount = 0;
        int batchCount = 0;
        
        // We synchronize on the method to prevent import/unassign operations 
        // from changing the cache while we iterate.
        
        try (RocksIterator iterator = db.newIterator();
             WriteBatch deleteBatch = new WriteBatch()) {
             
            iterator.seekToFirst();
            while (iterator.isValid()) {
                String listIdsValue = new String(iterator.value(), StandardCharsets.UTF_8);
                
                // Split the list IDs (e.g., "us_ads,malware")
                String[] domainListIds = listIdsValue.split(",");
                
                boolean isAssignedAndActive = false;
                for (String listId : domainListIds) {
                    // Uses the updated check that includes the ListType check
                    if (assignmentCache.isAssignedToAnyIP(listId.trim())) {
                        isAssignedAndActive = true;
                        break;
                    }
                }
                
                // If NONE of the associated List-IDs are active, DELETE the Domain key
                if (!isAssignedAndActive) {
                    deleteBatch.delete(iterator.key());
                    deletedCount++;
                    batchCount++;
                }

                if (batchCount >= BATCH_SIZE) {
                    db.write(new WriteOptions(), deleteBatch);
                    // Reset the batch for the next set of deletions
                    deleteBatch.clear();
                    batchCount = 0;
                }

                iterator.next();
            }
            
            // Write the remaining items in the batch
            if (batchCount > 0) {
                db.write(new WriteOptions(), deleteBatch);
            }
        }
        
        System.out.println(String.format("Cleanup completed. Total domains deleted: %d.", deletedCount));
    }

    /**
     * Provides access to the ListAssignmentCache for external management (like the REST controller).
     * @return The ListAssignmentCache instance.
     */
    public ListAssignmentCache getAssignmentCache() {
        return assignmentCache;
    }

    /**
     * Imports domains from a stream, using a batch process for efficiency.
     *
     * @param listId The ID of the list to tag the domains with.
     * @param stream The InputStream to read the domains from.
     * @return The number of domains successfully imported.
     */
    private int importListFromStream(String listId, InputStream stream) throws IOException, RocksDBException {
        int count = 0;
        int totalDomains = 0;
        
        // Using try-with-resources for WriteBatch and WriteOptions
        try (WriteBatch batch = new WriteBatch();
             BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            
            String line;
            while ((line = reader.readLine()) != null) {
                String domain = line.trim().toLowerCase(); // Normalize domain
                
                // Existing validation logic
                if (domain.isEmpty() || domain.startsWith("#") || !DOMAIN_PATTERN.matcher(domain).matches()) {
                    continue; 
                }

                // Append the listId to the existing value (list of IDs) or create a new one
                byte[] key = domain.getBytes(StandardCharsets.UTF_8);
                byte[] existingValue = db.get(key);
                String newListIds;
                
                if (existingValue != null) {
                    String existingListIds = new String(existingValue, StandardCharsets.UTF_8);
                    
                    // Simple check if the ID is already present to prevent duplicate list IDs
                    if (!Arrays.asList(existingListIds.split(",")).contains(listId)) {
                        newListIds = existingListIds + "," + listId;
                    } else {
                        newListIds = existingListIds;
                    }
                } else {
                    newListIds = listId;
                }

                batch.put(key, newListIds.getBytes(StandardCharsets.UTF_8));
                count++;
                totalDomains++;

                if (count >= BATCH_SIZE) {
                    db.write(new WriteOptions(), batch); // Use a new WriteOptions instance
                    batch.clear(); // Clear the batch for reuse
                    count = 0;
                }
            }
            
            // Write the final batch
            if (count > 0) {
                db.write(new WriteOptions(), batch);
            }
        }

        System.out.println(String.format("Successfully imported %d domains for list '%s'.", totalDomains, listId));
        return totalDomains;
    }


    /**
     * Checks if a domain is blocked for a given IP, using White-Before-Black logic.
     * * @param domain The domain to check.
     * @param ip     The IP address of the querying client.
     * @return true if the domain is blocked, false otherwise.
     * @throws RocksDBException 
     */
    public boolean isBlocked(String domain, String ip) throws RocksDBException {
        String targetDomain = domain.trim().toLowerCase();
        
        // Step 1: RocksDB lookup
        byte[] listIdsBytes = db.get(targetDomain.getBytes(StandardCharsets.UTF_8));

        if (listIdsBytes == null || listIdsBytes.length == 0) {
            return false; // Domain is not in any list
        }

        String listIdsValue = new String(listIdsBytes, StandardCharsets.UTF_8);
        String targetIp = ip.trim();

        boolean isWhitelisted = false;
        boolean isBlocked = false;

        // Step 2: Cache lookup and White-Before-Black check
        for (String listId : listIdsValue.split(",")) {
            String trimmedListId = listId.trim();
            if (trimmedListId.isEmpty()) continue;
            
            // Check 1: Is the list active (BLOCK/WHITE) and assigned to any IP? (For general check)
            if (!assignmentCache.isAssignedToAnyIP(trimmedListId)) {
                continue; 
            }

            // Check 2: Is the target IP assigned to this specific list?
            if (!assignmentCache.isAssignedToIP(trimmedListId, targetIp)) {
                // This specific list is NOT assigned to the specific IP, skip it.
                continue;
            }

            // Get the list type
            ListType type = assignmentCache.getListType(trimmedListId);

            if (type == ListType.WHITE) {
                isWhitelisted = true;
            } else if (type == ListType.BLOCK) {
                isBlocked = true;
            }
        }
        
        // Final decision: Whitelist takes precedence over Blocklist
        if (isWhitelisted) {
            return false;
        }
        
        if (isBlocked) {
            return true;
        }

        return false;
    }

    /**
     * Closes the RocksDB instance and saves the cache.
     */
    public void close() {
        if (assignmentCache != null) {
            // saveCacheToFile is synchronized
            assignmentCache.saveCacheToFile();
        }
        if (db != null) {
            db.close();
        }
    }
}
