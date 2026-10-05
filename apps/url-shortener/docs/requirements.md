# Requirements

## stories

- **1**
  - **id**: S-1
  - **title**: Create a short link without an account
  - **role**: visitor
  - **want**: submit a long URL and receive a short URL
  - **benefit**: I can share a compact link without signing up
  - **acceptanceCriteria**
    - **1**
      - **id**: S-1-1
      - **text**: Given a valid absolute http or https URL, when the visitor submits it, the service returns a short URL whose code is 7 base62 characters.
    - **2**
      - **id**: S-1-2
      - **text**: Given the same long URL submitted twice, when both requests succeed, the service returns two short URLs that each redirect to that long URL.
    - **3**
      - **id**: S-1-3
      - **text**: Given a submission, when the response is returned, it contains the short URL in a form the visitor can copy.
    - **4**
      - **id**: S-1-4
      - **text**: Given a missing or empty URL value, when the visitor submits it, the service rejects the request with a 400 response and creates no short link.
    - **5**
      - **id**: S-1-5
      - **text**: Given a value that is not an absolute http or https URL, when the visitor submits it, the service rejects the request with a 400 response and creates no short link.
- **2**
  - **id**: S-2
  - **title**: Follow a short link
  - **role**: anyone holding a short URL
  - **want**: open the short URL and be sent to the original long URL
  - **benefit**: I reach the intended page without knowing the long address
  - **acceptanceCriteria**
    - **1**
      - **id**: S-2-1
      - **text**: Given a short code that exists, when it is requested, the service responds with HTTP 302 and a Location header equal to the stored long URL.
    - **2**
      - **id**: S-2-2
      - **text**: Given a short code that does not exist, when it is requested, the service responds with HTTP 404 and no redirect.
    - **3**
      - **id**: S-2-3
      - **text**: Given a short link created earlier, when it is followed at any later time, it still redirects, because links do not expire and have no click limit.
    - **4**
      - **id**: S-2-4
      - **text**: Given a short link is followed, when the redirect is served, no click or usage data is recorded or exposed.
- **3**
  - **id**: S-3
  - **title**: Avoid duplicate short codes
  - **role**: service operator
  - **want**: each short code to map to exactly one long URL
  - **benefit**: followers always land on the link the creator intended
  - **acceptanceCriteria**
    - **1**
      - **id**: S-3-1
      - **text**: Given a generated code that already exists, when a new link is created, the service generates a different code and stores the new link under it.
    - **2**
      - **id**: S-3-2
      - **text**: Given any stored short code, when it is followed, the service redirects to the long URL that was stored with that code and to no other.
    - **3**
      - **id**: S-3-3
      - **text**: Given the service is restarted, when a previously created short code is followed, it still redirects to its stored long URL.
- **4**
  - **id**: S-4
  - **title**: Run the service offline in a container
  - **role**: developer running the test suite
  - **want**: the service to use only the Python standard library and SQLite and to run without network access
  - **benefit**: tests are reproducible in the offline container
  - **acceptanceCriteria**
    - **1**
      - **id**: S-4-1
      - **text**: Given the container has no outbound network access, when the test suite runs, all create and follow tests pass.
    - **2**
      - **id**: S-4-2
      - **text**: Given a fresh empty SQLite database, when the service starts, it creates the storage it needs and serves create and follow requests.
    - **3**
      - **id**: S-4-3
      - **text**: Given the service is started twice against the same SQLite file, when links created in the first run are followed in the second, they redirect correctly.

## openQuestions


## assumptions

- The service is exposed as a small HTTP service; the exact framework or handler style is left to the implementer, using only the Python standard library.
- The create endpoint accepts the long URL as a form field or JSON body; the exact request shape is left to the implementer.
- The short URL is returned as an absolute URL built from the request host, or as a path containing the code; the exact form is left to the implementer.
- The base62 alphabet and the collision retry limit are left to the implementer; retries continue until an unused code is found.
- The SQLite schema and file location are left to the implementer.
- No authentication, API keys, rate limiting, or abuse protection are included in this version.
- No custom aliases, expiry, click limits, click tracking, statistics, or listing endpoint are included in this version.
- Only http and https targets are accepted; other schemes are rejected.
- Error responses use plain HTTP status codes with no required body format.
- Concurrent creation of the same code is handled by the storage layer so that one code maps to one long URL.

