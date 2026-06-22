package org.nrg.xnatx.plugins.structimport.importer;

import org.junit.Test;
import org.nrg.xnatx.plugins.structimport.services.impl.csv.CsvBasedResourceIdentifierService;
import org.nrg.xnatx.plugins.structimport.services.impl.simple.SimpleResourceIdentifierService;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

public class ResourceIdentifierSelectorTest {

    private static final String TOGGLE_PARAM = "toggleStructuredSessionLabeling";

    @Test
    public void explicitResourceIdentifierParameterWins() {
        final Map<String, Object> parameters = new HashMap<>();
        parameters.put(ResourceIdentifierSelector.PARAM_RESOURCE_IDENTIFIER, "someCustomService");
        parameters.put(TOGGLE_PARAM, "simple");

        assertThat(ResourceIdentifierSelector.select(parameters), equalTo("someCustomService"));
    }

    @Test
    public void blankResourceIdentifierFallsThroughToToggle() {
        final Map<String, Object> parameters = new HashMap<>();
        parameters.put(ResourceIdentifierSelector.PARAM_RESOURCE_IDENTIFIER, "   ");

        assertThat(ResourceIdentifierSelector.select(parameters),
                   equalTo(CsvBasedResourceIdentifierService.class.getSimpleName()));
    }

    @Test
    public void noParametersDefaultsToCsvService() {
        assertThat(ResourceIdentifierSelector.select(Collections.emptyMap()),
                   equalTo(CsvBasedResourceIdentifierService.class.getSimpleName()));
    }

    @Test
    public void derivedToggleSelectsCsvService() {
        assertThat(ResourceIdentifierSelector.select(Collections.singletonMap(TOGGLE_PARAM, (Object) "derived")),
                   equalTo(CsvBasedResourceIdentifierService.class.getSimpleName()));
    }

    @Test
    public void nonDerivedToggleSelectsSimpleService() {
        assertThat(ResourceIdentifierSelector.select(Collections.singletonMap(TOGGLE_PARAM, (Object) "manual")),
                   equalTo(SimpleResourceIdentifierService.class.getSimpleName()));
    }
}
