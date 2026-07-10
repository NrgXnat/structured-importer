package org.nrg.xnatx.plugins.structimport.importer;

import org.junit.Test;

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
        parameters.put(ResourceIdentifierSelector.PARAM_TOGGLE_SESSION_LABELING, "simple");

        assertThat(ResourceIdentifierSelector.select(parameters), equalTo("someCustomService"));
    }

    @Test
    public void blankResourceIdentifierFallsThroughToToggle() {
        final Map<String, Object> parameters = new HashMap<>();
        parameters.put(ResourceIdentifierSelector.PARAM_RESOURCE_IDENTIFIER, "   ");

        assertThat(ResourceIdentifierSelector.select(parameters),
                   equalTo(ResourceIdentifierSelector.CSV_IDENTIFIER_SERVICE));
    }

    @Test
    public void noParametersDefaultsToCsvService() {
        assertThat(ResourceIdentifierSelector.select(Collections.emptyMap()),
                   equalTo(ResourceIdentifierSelector.CSV_IDENTIFIER_SERVICE));
    }

    @Test
    public void derivedToggleSelectsCsvService() {
        assertThat(ResourceIdentifierSelector.select(Collections.singletonMap(TOGGLE_PARAM, ResourceIdentifierSelector.DERIVED_SESSION_LABELING)),
                   equalTo(ResourceIdentifierSelector.CSV_IDENTIFIER_SERVICE));
    }

    @Test
    public void nonDerivedToggleSelectsManifestAwareManualService() {
        assertThat(ResourceIdentifierSelector.select(Collections.singletonMap(TOGGLE_PARAM, ResourceIdentifierSelector.MANUAL_SESSION_LABELING)),
                   equalTo(ResourceIdentifierSelector.MANUAL_IDENTIFIER_SERVICE));
    }

    @Test
    public void simpleServiceRemainsSelectableByExplicitParameter() {
        final Map<String, Object> parameters = new HashMap<>();
        parameters.put(ResourceIdentifierSelector.PARAM_RESOURCE_IDENTIFIER, ResourceIdentifierSelector.SIMPLE_IDENTIFIER_SERVICE);
        parameters.put(ResourceIdentifierSelector.PARAM_TOGGLE_SESSION_LABELING, ResourceIdentifierSelector.MANUAL_SESSION_LABELING);

        assertThat(ResourceIdentifierSelector.select(parameters),
                   equalTo(ResourceIdentifierSelector.SIMPLE_IDENTIFIER_SERVICE));
    }
}
