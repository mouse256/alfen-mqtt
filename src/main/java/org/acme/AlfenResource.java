package org.acme;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.acme.data.Categories;
import org.acme.data.PropertyCatRsp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.Optional;

@Path("/api")
public class AlfenResource {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    @Inject
    private AlfenController alfenController;

    @GET
    @Path("categories")
    @Produces(MediaType.APPLICATION_JSON)
    public Categories categories() {
        return alfenController.getCategories()
                .orElseThrow(() -> new InternalServerErrorException("Can't fetch categories"));
    }

    @GET
    @Path("properties/{category}")
    @Produces(MediaType.APPLICATION_JSON)
    public PropertyCatRsp properties(@PathParam("category")String category) {
        return alfenController.getProperties(category)
                .orElseThrow(() -> new InternalServerErrorException("Can't fetch categories"));
    }
}
