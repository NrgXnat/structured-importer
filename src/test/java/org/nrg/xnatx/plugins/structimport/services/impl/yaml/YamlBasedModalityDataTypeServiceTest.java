package org.nrg.xnatx.plugins.structimport.services.impl.yaml;

import org.junit.Test;
import org.nrg.xnatx.plugins.structimport.models.ModalityMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;

public class YamlBasedModalityDataTypeServiceTest {

    private static final String TEST_BASE    = "modality-test/base.yaml";
    private static final String TEST_PATTERN = "classpath*:modality-test/overrides/*.yaml";

    @Test
    public void loadsDefaultConfiguration() {
        final YamlBasedModalityDataTypeService service = new YamlBasedModalityDataTypeService();
        final Map<String, ModalityMapping>     mappings = service.getModalityMappings();

        assertThat(mappings.size(), is(greaterThan(20)));
        assertThat(mappings, hasKey("MR"));

        final ModalityMapping mr = mappings.get("MR");
        assertThat(mr.getScan(), equalTo("xnat:mrScanData"));
        assertThat(mr.getSession(), equalTo("xnat:mrSessionData"));
        assertThat(mr.getGroupOrDefault(), is(0));
    }

    @Test
    public void defaultConfigurationIncludesScanOnlyModalities() {
        final YamlBasedModalityDataTypeService service = new YamlBasedModalityDataTypeService();

        final Optional<ModalityMapping> sc = service.getModalityMapping("SC");
        assertThat(sc.isPresent(), is(true));
        assertThat(sc.get().hasScanDataType(), is(true));
        assertThat(sc.get().hasSessionDataType(), is(false));

        // scan-only modalities are excluded from the session list
        for (final ModalityMapping mapping : service.getSessionModalities()) {
            assertThat(mapping.hasSessionDataType(), is(true));
        }
    }

    @Test
    public void sessionModalitiesAreOrderedByGroupThenModality() {
        final YamlBasedModalityDataTypeService service  = new YamlBasedModalityDataTypeService();
        final List<ModalityMapping>            sessions = service.getSessionModalities();

        int lastGroup = -1;
        String lastModality = null;
        for (final ModalityMapping mapping : sessions) {
            if (mapping.getGroupOrDefault() == lastGroup) {
                assertThat(mapping.getModality().compareTo(lastModality) > 0, is(true));
            } else {
                assertThat(mapping.getGroupOrDefault() > lastGroup, is(true));
            }
            lastGroup    = mapping.getGroupOrDefault();
            lastModality = mapping.getModality();
        }
        assertThat(sessions.get(0).getGroupOrDefault(), is(0));
    }

    @Test
    public void lookupIsCaseInsensitive() {
        final YamlBasedModalityDataTypeService service = new YamlBasedModalityDataTypeService();
        assertThat(service.getModalityMapping("mr").isPresent(), is(true));
        assertThat(service.getModalityMapping(" MR ").isPresent(), is(true));
        assertThat(service.getModalityMapping("NOPE").isPresent(), is(false));
        assertThat(service.getModalityMapping(null).isPresent(), is(false));
        assertThat(service.getModalityMapping("  ").isPresent(), is(false));
    }

    @Test
    public void overridesMergePerFieldInStableOrder() {
        final YamlBasedModalityDataTypeService service = new YamlBasedModalityDataTypeService(TEST_BASE, TEST_PATTERN);

        // b-override.yaml loads after a-override.yaml, so its group wins;
        // the scan/session values from the base file are retained
        final ModalityMapping mr = service.getModalityMapping("MR").orElse(null);
        assertThat(mr, not(nullValue()));
        assertThat(mr.getGroupOrDefault(), is(2));
        assertThat(mr.getScan(), equalTo("xnat:mrScanData"));
        assertThat(mr.getSession(), equalTo("xnat:mrSessionData"));
    }

    @Test
    public void blankStringInOverrideClearsInheritedField() {
        final YamlBasedModalityDataTypeService service = new YamlBasedModalityDataTypeService(TEST_BASE, TEST_PATTERN);

        final ModalityMapping us = service.getModalityMapping("US").orElse(null);
        assertThat(us, not(nullValue()));
        assertThat(us.hasSessionDataType(), is(false));
        assertThat(us.getScan(), equalTo("xnat:usScanData"));

        final List<String> sessionModalities = new ArrayList<>();
        for (final ModalityMapping mapping : service.getSessionModalities()) {
            sessionModalities.add(mapping.getModality());
        }
        assertThat(sessionModalities, contains("XNEW", "MR"));
    }

    @Test
    public void overridesCanAddNewModalities() {
        final YamlBasedModalityDataTypeService service = new YamlBasedModalityDataTypeService(TEST_BASE, TEST_PATTERN);

        final ModalityMapping added = service.getModalityMapping("XNEW").orElse(null);
        assertThat(added, not(nullValue()));
        assertThat(added.getScan(), equalTo("xnat:xnewScanData"));
        assertThat(added.getSession(), equalTo("xnat:xnewSessionData"));
        assertThat(added.getGroupOrDefault(), is(1));
    }

    @Test
    public void malformedFilesAreSkipped() {
        // c-malformed.yaml is in the override directory; loading must survive it
        final YamlBasedModalityDataTypeService service = new YamlBasedModalityDataTypeService(TEST_BASE, TEST_PATTERN);
        assertThat(service.getModalityMappings().size(), is(4)); // MR, US, SC, XNEW
    }

    @Test
    public void missingCoreConfigurationYieldsOverridesOnly() {
        final YamlBasedModalityDataTypeService service = new YamlBasedModalityDataTypeService("does/not/exist.yaml", TEST_PATTERN);
        assertThat(service.getModalityMappings(), hasKey("XNEW"));
        assertThat(service.getModalityMappings(), not(hasKey("SC")));
    }

    @Test
    public void modalitiesWithUninstalledDataTypesAreFilteredOut() {
        // XNEW's data types are "not installed"; MR, US, and SC survive
        final YamlBasedModalityDataTypeService service = filteringService(dataType -> !dataType.startsWith("xnat:xnew"));

        assertThat(service.getModalityMappings(), not(hasKey("XNEW")));
        assertThat(service.getModalityMappings(), hasKey("MR"));
        assertThat(service.getModalityMappings(), hasKey("US"));
        assertThat(service.getModalityMappings(), hasKey("SC"));
        assertThat(service.getModalityMapping("XNEW").isPresent(), is(false));
    }

    @Test
    public void modalityIsFilteredWhenAnyOfItsDataTypesIsMissing() {
        // only MR's scan type is missing, but the whole modality is dropped;
        // XNEW (whose types are all "installed") remains the only session modality
        final YamlBasedModalityDataTypeService service = filteringService(dataType -> !"xnat:mrScanData".equals(dataType));

        assertThat(service.getModalityMappings(), not(hasKey("MR")));
        final List<String> sessionModalities = new ArrayList<>();
        for (final ModalityMapping mapping : service.getSessionModalities()) {
            sessionModalities.add(mapping.getModality());
        }
        assertThat(sessionModalities, contains("XNEW"));
    }

    @Test
    public void unfilteredConfigurationIsServedUntilSchemaIsReady() {
        final YamlBasedModalityDataTypeService service = new YamlBasedModalityDataTypeService(TEST_BASE, TEST_PATTERN) {
            @Override
            protected boolean isSchemaInitialized() {
                return false;
            }

            @Override
            protected boolean isDataTypeInstalled(final String dataType) {
                return false; // would filter everything if it ran
            }
        };
        assertThat(service.getModalityMappings().isEmpty(), is(false));
    }

    private static YamlBasedModalityDataTypeService filteringService(final java.util.function.Predicate<String> installed) {
        return new YamlBasedModalityDataTypeService(TEST_BASE, TEST_PATTERN) {
            @Override
            protected boolean isSchemaInitialized() {
                return true;
            }

            @Override
            protected boolean isDataTypeInstalled(final String dataType) {
                return installed.test(dataType);
            }
        };
    }
}
