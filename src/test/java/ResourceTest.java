import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.acme.data.Categories;
import org.acme.data.PropertyCatRsp;
import org.acme.data.PropertyParsed;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

@QuarkusTest
@QuarkusTestResource(MockAlfenDeviceResource.class)
public class ResourceTest {

    @InjectMockAlfenDevice
    private MockAlfenDevice mockAlfenDevice;

    @Inject
    private ObjectMapper objectMapper;
    private static List<Tuple> ids;
    private static PropertyCatRsp propertyCat1a;
    private static PropertyCatRsp propertyCat1b;
    private static PropertyCatRsp propertyCat2;
    private static List<PropertyParsed> propertyCatFull;

    private final Categories dummyCategories = new Categories("v2", List.of("cat1", "cat2"));


    @BeforeAll
    public static void beforeAll() throws IOException {
        Properties props = new Properties();
        props.load(ResourceTest.class.getResourceAsStream("/ids.properties"));
        ids = props.entrySet().stream()
                .map(e -> new Tuple((String) e.getKey(), (String) e.getValue()))
                .collect(Collectors.toList());

        propertyCat1a = new PropertyCatRsp(2,
                IntStream.range(0, 32).mapToObj(i -> {
                    PropValue propValue = getPropValue(i);
                    return new PropertyCatRsp.Property(ids.get(i).key, 0, propValue.type, 1, "cat", propValue.value.toString());
                }).collect(Collectors.toList())
                , 0, 47);
        propertyCat1b = new PropertyCatRsp(2,
                IntStream.range(32, 47).mapToObj(i -> {
                    PropValue propValue = getPropValue(i);
                    return new PropertyCatRsp.Property(ids.get(i).key, 0, propValue.type, 1, "cat", propValue.value.toString());
                }).collect(Collectors.toList())
                , 32, 47);
        propertyCatFull =
                IntStream.range(0, 47)
                        .mapToObj(i -> new PropertyParsed(ids.get(i).value, ids.get(i).key, getPropValue(i).value))
                        .collect(Collectors.toList());

        propertyCat2 = new PropertyCatRsp(2,
                List.of(new PropertyCatRsp.Property(ids.getFirst().key, 0, 9, 1, "cat", "test")),
                0, 1);


    }

    private static PropValue getPropValue(int i) {
        if (i < 5) {
            return new PropValue(2, i);
        } else if (i < 10) {
            return new PropValue(5, i);
        } else if (i < 15) {
            return new PropValue(8, (double) i);
        } else {
            return new PropValue(9, "prop" + i);
        }
    }

    @BeforeEach
    public void beforeEach() throws JsonProcessingException {
        String cats = objectMapper.writeValueAsString(dummyCategories);
        mockAlfenDevice.setCategoriesFunction(ctx -> ctx.response().end(cats));
        mockAlfenDevice.setPropertiesFunction(ctx -> {
            String cat = ctx.request().getParam("cat");
            if (cat == null || !dummyCategories.categories().contains(cat)) {
                return ctx.response().setStatusCode(404).end();
            }
            try {
                if (cat.equals("cat1")) {
                    String offset = ctx.request().getParam("offset");
                    if (offset == null) {
                        return ctx.response().end(objectMapper.writeValueAsString(propertyCat1a));
                    } else if ("32".equals(offset)) {
                        return ctx.response().end(objectMapper.writeValueAsString(propertyCat1b));
                    }
                } else if (cat.equals("cat2")) {
                    return ctx.response().end(objectMapper.writeValueAsString(propertyCat2));
                }
                return ctx.response().setStatusCode(500).end();
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        });
    }


    @Test
    public void testCategories() throws JsonProcessingException {
        String cats = objectMapper.writeValueAsString(dummyCategories);
        given()
                .when().get("/api/categories")
                .then()
                .statusCode(200)
                .body(is(cats));
    }

    @Test
    public void testPropertyInvalidCategory() throws JsonProcessingException {
        given()
                .when().get("/api/properties/invalid")
                .then()
                .statusCode(400);
    }

    @Test
    public void testPropertyValidCategoryShort() throws JsonProcessingException {
        List<PropertyParsed> expected =
                List.of(new PropertyParsed(ids.getFirst().value, ids.getFirst().key, "test"));
        String rsp = objectMapper.writeValueAsString(expected);
        given()
                .when().get("/api/properties/cat2")
                .then()
                .statusCode(200)
                .body(is(rsp));
    }

    @Test
    public void testPropertyValidCategoryLong() throws JsonProcessingException {
        String rsp = objectMapper.writeValueAsString(propertyCatFull);
        given()
                .when().get("/api/properties/cat1")
                .then()
                .statusCode(200)
                .body(is(rsp));
    }

    private record Tuple(String key, String value) {
    }

    private record PropValue(int type, Object value) {
    }
}
