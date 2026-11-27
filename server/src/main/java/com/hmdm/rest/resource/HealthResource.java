package com.hmdm.rest.resource;

import com.hmdm.persistence.mapper.HealthMapper;
import com.hmdm.rest.resource.support.HealthProbe;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.CacheControl;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.File;

/**
 * <p>Deep health probe: {@code GET /rest/public/health} (the edge serves it as {@code /healthz/deep}).</p>
 *
 * <p>200 {@code {"status":"ok","checks":{"db":"ok","files":"ok"}}} when this instance can serve; 503 with the same
 * shape when the database does not answer or the file store is not writable. No authentication and no data: only
 * fixed words.</p>
 *
 * <p>Draining: a deployment creates the marker file named by the system property {@code dallycontrol.drain.file}
 * (default {@code /tmp/dallycontrol.drain}, local to the container) so the edge stops routing here before the stop.
 * Only the edge's own health check (header {@code X-DallyControl-Health: lb}, which the edge strips from outside
 * requests) is told so, with 503 {@code "status":"draining"}: to a monitor, an instance that is being retired but
 * still answers correctly is healthy, so a deployment is never recorded as downtime.</p>
 */
@Singleton
@Path("/public/health")
@Api(tags = {"Health"})
public class HealthResource {

    static final String DRAIN_FILE_PROPERTY = "dallycontrol.drain.file";
    static final String DEFAULT_DRAIN_FILE = "/tmp/dallycontrol.drain";
    static final String HEALTH_ROLE_HEADER = "X-DallyControl-Health";
    static final String LOAD_BALANCER_ROLE = "lb";

    private HealthProbe probe;

    /** A constructor required by Swagger. */
    public HealthResource() {
    }

    @Inject
    public HealthResource(HealthMapper healthMapper, @Named("files.directory") String filesDirectory) {
        File drainFile = new File(System.getProperty(DRAIN_FILE_PROPERTY, DEFAULT_DRAIN_FILE));
        File files = new File(filesDirectory);
        this.probe = new HealthProbe(drainFile::exists, 5000, 2000, System::currentTimeMillis)
                .add("db", () -> healthMapper.ping() == 1)
                .add("files", () -> files.isDirectory() && files.canWrite());
    }

    @ApiOperation(value = "Deep health probe", notes = "Checks the database and the file store. 200 when ready, 503 otherwise.")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response health(@HeaderParam(HEALTH_ROLE_HEADER) String role) {
        HealthProbe.Result result = probe.check(LOAD_BALANCER_ROLE.equals(role));
        CacheControl noStore = new CacheControl();
        noStore.setNoStore(true);
        return Response.status(result.isReady() ? 200 : 503)
                .entity(result.toJson())
                .type(MediaType.APPLICATION_JSON)
                .cacheControl(noStore)
                .build();
    }
}
