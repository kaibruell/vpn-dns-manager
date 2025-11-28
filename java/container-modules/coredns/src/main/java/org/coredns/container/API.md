# IPBlocker REST API

## Endpoints

### 1. POST /api/list/upload
Upload a domain list and assign it to an IP address.

**Method:** `POST`

**Parameters:**
- `listId` (required): Identifier for the list (e.g., `us_ads`, `malware`)
- `ip` (required): Target IP address
- `listType` (required): Type of list - `BLOCK` or `WHITE`

**Request Body:** Plain text file with domains (one per line)

**Example:**
```bash
curl -X POST "http://localhost:8080/api/list/upload?listId=us_ads&ip=192.168.1.1&listType=BLOCK" \
  --data-binary @blacklist.txt
```

**Response (200 OK):**
```
List 'us_ads' (BLOCK) successfully imported (1234 domains) and assigned to IP 192.168.1.1.
```


### 2. PUT /api/list/assignment
Assign or update a list assignment to an IP address with a specific type (BLOCK or WHITE).

**Method:** `PUT`

**Parameters:**
- `listId` (required): Identifier for the list
- `ip` (required): Target IP address
- `listType` (required): Type of list - `BLOCK` or `WHITE` (cannot be `NONE`)

**Example:**
```bash
curl -X PUT "http://localhost:8080/api/list/assignment?listId=us_ads&ip=192.168.1.1&listType=WHITE"
```

**Response (200 OK):**
```
List 'us_ads' successfully assigned to IP 192.168.1.1 with type BLOCK.
```

---

### 3. DELETE /api/list/assignment
Unassign a list from an IP address.

**Method:** `DELETE`

**Parameters:**
- `listId` (required): Identifier for the list
- `ip` (required): Target IP address

**Example:**
```bash
curl -X DELETE "http://localhost:8080/api/list/assignment?listId=us_ads&ip=192.168.1.1"
```

**Response (200 OK):**
```
List 'us_ads' successfully unassigned from IP 192.168.1.1.
```

---

### 4. POST /api/list/cleanup
Cleanup unassigned domains from the database. Removes all domains that are no longer assigned to any active list.

**Method:** `POST`

**Parameters:** None

**Example:**
```bash
curl -X POST "http://localhost:8080/api/list/cleanup"
```

**Response (200 OK):**
```
Cleanup of unassigned domains completed.
```

---

### 5. GET /api/domain/check
Check if a domain is blocked for a specific IP address.

**Method:** `GET`

**Parameters:**
- `domain` (required): Domain to check
- `ip` (required): Client IP address

**Example:**
```bash
curl -X GET "http://localhost:8080/api/domain/check?domain=badsite.com&ip=192.168.1.1"
```

**Response (200 OK):**
```json
true
```
or
```json
false
```

---

## Data Model

### ListType
- `BLOCK`: Domain is blocked for the specified IP
- `WHITE`: Domain is whitelisted for the specified IP
- `NONE`: Used internally for unassignment (not valid for PUT requests)

### Logic: White-Before-Black
When checking if a domain is blocked:
1. If the domain is whitelisted for the IP (`WHITE`), it is allowed
2. If the domain is blocked for the IP (`BLOCK`), it is blocked
3. Otherwise, it is allowed

---

## Error Responses

All error responses use appropriate HTTP status codes:
- `400 Bad Request`: Missing or invalid parameters
- `404 Not Found`: Resource not found
- `405 Method Not Allowed`: Incorrect HTTP method
- `500 Internal Server Error`: Server-side error

**Example error response:**
```
PUT requires parameters: listId, ip, and listType (BLOCK or WHITE)
```
