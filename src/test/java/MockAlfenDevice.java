import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.function.Function;

public class MockAlfenDevice implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final HttpServer server;
    private final Router router;

    public MockAlfenDevice(Vertx vertx, int port) {
        try {
            server = vertx.createHttpServer();

            router = Router.router(vertx);
            router
                    .post("/api/login")
                    .respond(
                            ctx -> ctx
                                    .response()
                                    //.putHeader("Content-Type", "text/plain")
                                    .end("OK"));


            server.requestHandler(router).listen(port);
            LOG.info("Mock alfen device listening on port " + port);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void setCategoriesFunction(Function<RoutingContext, Future<Void>> categoriesFunction) {
        router.get("/api/categories")
                .respond(categoriesFunction);
    }
    public void setPropertiesFunction(Function<RoutingContext, Future<Void>> propertiesFunction) {
        router.get("/api/prop")
                .respond(propertiesFunction);
    }

    @Override
    public void close() throws Exception {
        LOG.info("Closing mock alfen device");
        server.close();
    }
}
