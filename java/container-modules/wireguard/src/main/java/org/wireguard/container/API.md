
# WireGuard Manager REST API

## Endpoints

### 1. POST /api/service/start
Start the WireGuard service.

**Method:** `POST`

**Parameters:** None

**Example:**
```bash
curl -X POST "http://localhost:2233/api/service/start"
```

**Response (200 OK):**

```json
{
  "status": "SUCCESS"
}
```

Possible status values:

  - `SUCCESS`: WireGuard was successfully started
  - `ALREADY_DONE`: WireGuard was already running
  - `ERROR`: An error occurred while starting WireGuard

-----

### 2\. POST /api/service/stop

Stop the WireGuard service.

**Method:** `POST`

**Parameters:** None

**Example:**

```bash
curl -X POST "http://localhost:2233/api/service/stop"
```

**Response (200 OK):**

```json
{
  "status": "SUCCESS"
}
```

Possible status values:

  - `SUCCESS`: WireGuard was successfully stopped
  - `ALREADY_DONE`: WireGuard was already stopped
  - `ERROR`: An error occurred while stopping WireGuard

-----

### 3\. GET /api/peers

Retrieve all connected WireGuard peers.

**Method:** `GET`

**Parameters:** None

**Example:**

```bash
curl -X GET "http://localhost:2233/api/peers"
```

**Response (200 OK):**

```json
[
  {
    "publicKey": "ABCDEF1234567890...",
    "psk": "GHIJKL0987654321...",
    "endpoint": "203.0.113.45:51820",
    "allowedIps": "10.13.13.2/32",
    "lastHandshake": 1732800000,
    "transferRx": 10240,
    "transferTx": 20480,
    "persistentKeepalive": 25,
    "isActive": true
  }
]
```

-----

### 4\. POST /api/peers/add

Add a new peer to the WireGuard configuration.

**⚠️ SECURITY NOTE:** This endpoint returns sensitive information, including the private key and the full configuration file. **Ensure this API is accessed only over a secure HTTPS connection.**

**Method:** `POST`

**Parameters:**

  - `name` (required): Peer identifier/name
  - `ip` (optional): Specific IP address to assign to the peer (e.g., `10.13.13.2`)

**Example:**

```bash
curl -X POST "http://localhost:2233/api/peers/add?name=mobile_client"
```

**Response (200 OK):**
Returns the full peer details including keys and configuration text.

```json
{
  "name": "mobile_client",
  "publicKey": "PUBLIC_KEY_Hash...",
  "privateKey": "PRIVATE_KEY_Hash...",
  "presharedKey": "PSK_Hash...",
  "ip": "10.13.13.2",
  "config": "[Interface]\nAddress = 10.13.13.2/24\nPrivateKey = PRIVATE_KEY_Hash...\n..."
}
```

-----

### 5\. POST /api/peers/remove

Remove a peer from the WireGuard configuration.

**Method:** `POST`

**Parameters:**

  - `name` (required): Peer identifier/name to remove

**Example:**

```bash
curl -X POST "http://localhost:2233/api/peers/remove?name=mobile_client"
```

**Response (200 OK):**

```json
{
  "success": true
}
```

-----

## Configuration

### Environment Variables

  - `WG_MANAGER_REST_URL` (optional): Address to bind the REST API server to. Default: `0.0.0.0`
  - `WG_MANAGER_REST_PORT` (optional): Port for the REST API server. Default: `2233`
  - `WG_AUTOSTART` (optional): Automatically start WireGuard on application startup. Set to `true` to enable. Default: `false`

-----

## Error Responses

All error responses use appropriate HTTP status codes and return JSON.

**Note:** For `500 Internal Server Error`, the API returns a generic message to prevent leaking stack traces or sensitive internal details.

  - `400 Bad Request`: Missing or invalid parameters (e.g., missing name)
  - `405 Method Not Allowed`: Incorrect HTTP method (e.g., using GET instead of POST)
  - `500 Internal Server Error`: Generic server-side error

**Example error response:**

```json
{
  "error": "Missing required parameter: name"
}
```

-----

## Data Model

### ServiceStatus

  - `SUCCESS`: Operation completed successfully
  - `ALREADY_DONE`: Operation was not needed (service already in desired state)
  - `ERROR`: Operation failed

### PeerDetails (Add Response)

  - `name`: Name of the peer
  - `publicKey`: WireGuard Public Key
  - `privateKey`: WireGuard Private Key (Sensitive\!)
  - `presharedKey`: WireGuard Preshared Key (Sensitive\!)
  - `ip`: Assigned IP address
  - `config`: Full content of the `.conf` file for the client

<!-- end list -->

```
