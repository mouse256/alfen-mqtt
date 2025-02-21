package org.muizenhol.alfen;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.muizenhol.alfen.data.Categories;
import org.muizenhol.alfen.data.PropertyParsed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.List;

@Path("/alfen/{device}")
public class AlfenResource {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    @Inject
    private AlfenController alfenController;

    @GET
    @Path("categories")
    @Produces(MediaType.APPLICATION_JSON)
    public Categories categories(@PathParam("device") String device) {
        return alfenController.getCategories(device)
                .orElseThrow(() -> new InternalServerErrorException("Can't fetch categories"));
    }

    @GET
    @Path("properties/{category}")
    @Produces(MediaType.APPLICATION_JSON)
    public List<PropertyParsed> properties(@PathParam("device") String device, @PathParam("category") String category) {
        return alfenController.getProperties(device, category);
    }
}
