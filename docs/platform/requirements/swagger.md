# Swagger (OpenAPI) documentation requirements

## Request

The controller's existing HTTP API (`POST /v1/runs` and `GET /v1/runs/{id}`, see `run-api.md`) needs a published, machine-readable API document and a browsable page that shows it, so that an API client can learn the contract without reading the code. No endpoint is added or changed.

## Ambiguities and assumptions

- **Document and page locations are assumed to be the conventional ones:** the OpenAPI document as JSON at `/v3/api-docs` and the browsable page at `/swagger-ui/index.html`. Neither is versioned, like the health endpoint (RUN-3, AC-RUN-3.4). The design step may change the paths; the criteria then change with the user's approval.
- **The document describes only the two run endpoints.** The health endpoint and the document and page endpoints themselves are not listed in it.
- **Both are open to any caller,** as the rest of the API is today. Authentication is out of scope.
- **Open question: compatibility.** It is not known whether a documentation library exists that works with Spring Boot 4.1.1. The design step must check this first; if none does, the user decides before any story is coded. The stories do not name a library.
- **The documented limit is 20,000 characters,** taken from RUN-5. If that limit changes, the document must change with it.

## User stories and acceptance criteria

### SWAG-1 Publish the API document

Status: coded

As an API client, I want the controller to serve an OpenAPI document of its run endpoints, so that I can learn the contract without reading the code.

- AC-SWAG-1.1 `GET /v3/api-docs` returns 200 with a JSON body that declares an OpenAPI version and a title.
- AC-SWAG-1.2 The document lists `POST /v1/runs` and `GET /v1/runs/{id}`, and no other path.
- AC-SWAG-1.3 The document describes the request body of `POST /v1/runs` as an object with a required text field `requirement` that has a maximum length of 20,000.
- AC-SWAG-1.4 The document describes the success bodies: `{id}` for create, and `id`, `requirement`, `status`, `createdAt`, `updatedAt`, `version` for read, with `status` limited to the values the API can return today (`CREATED`).

### SWAG-2 Document the responses and errors

Status: coded

As an API client, I want each endpoint's responses and errors documented, so that I know what to expect, including on failure.

- AC-SWAG-2.1 `POST /v1/runs` documents 201 (with the `Location` header), 400 and 415. `GET /v1/runs/{id}` documents 200, 400 and 404.
- AC-SWAG-2.2 Every documented error response uses the content type `application/problem+json` and a body with `type`, `title`, `status` and `detail`, as RUN-4 describes.
- AC-SWAG-2.3 Each operation and each documented response has a non-empty summary or description.

### SWAG-3 Browse the API in a page

Status: open

As an API client, I want a page in the browser that shows the API and lets me call it, so that I can try the endpoints without writing a client.

- AC-SWAG-3.1 `GET /swagger-ui/index.html` returns 200 with an HTML page.
- AC-SWAG-3.2 The page loads the document of SWAG-1 (it refers to `/v3/api-docs`, not to a copy).
- AC-SWAG-3.3 After the document and page are added, `POST /v1/runs` and `GET /v1/runs/{id}` return the same statuses and bodies as RUN-1 to RUN-7 describe, and a request to an unknown path still returns 404 in the RUN-4 format.

## Out of scope

- Adding, changing, or removing any endpoint.
- Authentication, authorisation, and Spring Security.
- Changing the look of the page, or its branding.
- Generating client code or server stubs from the document.
- Versioning the document or serving more than one version.
- Documenting the health endpoint or any endpoint added later (each new endpoint is documented in its own feature).
- Extra tooling: no document linting, no contract testing against a live service, no publishing outside the running controller.
