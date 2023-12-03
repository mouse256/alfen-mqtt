import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.acme.data.Categories;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

@QuarkusTest
@QuarkusTestResource(MockAlfenDeviceResource.class)
public class ResourceTest {

    @InjectMockAlfenDevice
    private MockAlfenDevice mockAlfenDevice;

    @Inject
    private ObjectMapper objectMapper;


    @Test
    public void testCategories() throws JsonProcessingException {

        Categories dummyCategories = new Categories("v2", List.of("cat1", "cat2"));
        String cats = objectMapper.writeValueAsString(dummyCategories);
        mockAlfenDevice.setCategoriesFunction(ctx -> ctx.response().end(cats));

        given()
                .when().get("/api/categories")
                .then()
                .statusCode(200)
                .body(is(cats));
    }

}
