package com.overwatch.api.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

/**
 * Serves the Postman collection and environment for download.
 *
 * <p>The API page in the dashboard lets you fire any request in the browser, which
 * is enough to see a response but not enough to keep. Postman is where a reviewer
 * will actually poke at this — saved variables, a captured alert id, a history of
 * what they tried — so the collection needs to be one click away from the page
 * that describes the endpoints, not a file path in a README.
 *
 * <p>The files are the ones in {@code /tools/postman}, copied onto this service's
 * classpath by the build (see this module's POM). That indirection is the point:
 * the collection under version control and the collection you can download are
 * the same bytes, so the download cannot drift from the repository the way a
 * second copy in {@code src/main/resources} would.
 *
 * <p>{@code Content-Disposition: attachment} rather than letting the browser render
 * it — a 40KB JSON document displayed inline is not what anybody wanted, and
 * Postman imports a file, not a tab.
 */
@RestController
@RequestMapping("/api/postman")
@Tag(name = "Postman", description = "The Postman collection and environment, for download")
public class PostmanController {

    private static final String COLLECTION = "Overwatch.postman_collection.json";
    private static final String ENVIRONMENT = "Overwatch-Local.postman_environment.json";

    @GetMapping("/collection")
    @Operation(summary = "Download the Postman collection",
            description = """
                    The collection under version control in /tools/postman, served
                    as a download. Import it into Postman together with the
                    environment below, which supplies `baseUrl` and the id
                    variables the requests interpolate.""")
    public ResponseEntity<Resource> collection() {
        return attachment(COLLECTION);
    }

    @GetMapping("/environment")
    @Operation(summary = "Download the Postman environment",
            description = """
                    Sets `baseUrl` to this service and `simulatorUrl` to the
                    simulator, plus the `alertId` and `ruleId` placeholders the
                    collection's parameterised requests read. Import it alongside
                    the collection and select it, or every request resolves to a
                    URL of literal `{{baseUrl}}`.""")
    public ResponseEntity<Resource> environment() {
        return attachment(ENVIRONMENT);
    }

    private ResponseEntity<Resource> attachment(String filename) {
        ClassPathResource resource = new ClassPathResource("postman/" + filename);
        if (!resource.exists()) {
            // Not a 404: the caller asked for something this service promises to
            // have, so a missing file is a packaging fault here rather than a bad
            // request there. Said plainly, because the cause is always the same
            // one — the build's copy-postman execution did not run.
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR,
                    filename + " is not on the classpath — the build did not copy "
                            + "/tools/postman into this service's resources.");
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(resource);
    }
}
