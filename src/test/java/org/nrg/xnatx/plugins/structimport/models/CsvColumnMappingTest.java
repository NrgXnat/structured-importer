package org.nrg.xnatx.plugins.structimport.models;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;

public class CsvColumnMappingTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void requiredDefaultsToTrueWhenUnset() {
        final CsvColumnMapping mapping = CsvColumnMapping.builder()
                                                         .column("Scan ID")
                                                         .property("xnat:imageScanData/ID")
                                                         .build();
        assertThat(mapping.getRequired(), is((Boolean) null));
        assertThat(mapping.isRequiredColumn(), is(true));
    }

    @Test
    public void requiredFalseIsRespected() {
        final CsvColumnMapping mapping = CsvColumnMapping.builder()
                                                         .column("Start Date")
                                                         .property("xnat:imageScanData/start_date")
                                                         .required(false)
                                                         .build();
        assertThat(mapping.isRequiredColumn(), is(false));
    }

    @Test
    public void requiredTrueIsRespected() {
        final CsvColumnMapping mapping = CsvColumnMapping.builder()
                                                         .column("Scan ID")
                                                         .property("xnat:imageScanData/ID")
                                                         .required(true)
                                                         .build();
        assertThat(mapping.isRequiredColumn(), is(true));
    }

    @Test
    public void nonNullInclusionOmitsUnsetFields() throws Exception {
        final CsvColumnMapping mapping = CsvColumnMapping.builder()
                                                         .column("Scan ID")
                                                         .property("xnat:imageScanData/ID")
                                                         .build();
        final String json = mapper.writeValueAsString(mapping);
        assertThat(json, containsString("\"column\":\"Scan ID\""));
        assertThat(json, containsString("\"property\":\"xnat:imageScanData/ID\""));
        assertThat(json, not(containsString("required")));
        assertThat(json, not(containsString("validation")));
        assertThat(json, not(containsString("requiredColumn")));
    }

    @Test
    public void serializesAllSetFields() throws Exception {
        final CsvColumnMapping mapping = CsvColumnMapping.builder()
                                                         .column("Subject Weight (g)")
                                                         .property("xnat:subjectData/demographics/weight")
                                                         .required(false)
                                                         .validation("^\\d+(\\.\\d+)?$")
                                                         .build();
        final String json = mapper.writeValueAsString(mapping);
        assertThat(json, containsString("\"column\":\"Subject Weight (g)\""));
        assertThat(json, containsString("\"required\":false"));
        assertThat(json, containsString("\"validation\":\"^\\\\d+(\\\\.\\\\d+)?$\""));
    }

    @Test
    public void roundTripsThroughJson() throws Exception {
        final CsvColumnMapping mapping = CsvColumnMapping.builder()
                                                         .column("Resource Name")
                                                         .property("xnat:abstractResource/label")
                                                         .required(false)
                                                         .validation("^[A-Za-z_]{1,32}$")
                                                         .build();
        final String           json     = mapper.writeValueAsString(mapping);
        final CsvColumnMapping restored = mapper.readValue(json, CsvColumnMapping.class);
        assertThat(restored, equalTo(mapping));
        assertThat(restored.isRequiredColumn(), is(false));
    }

    @Test
    public void deserializingWithoutRequiredDefaultsToRequiredTrue() throws Exception {
        final String           json    = "{\"column\":\"Scan ID\",\"property\":\"xnat:imageScanData/ID\"}";
        final CsvColumnMapping mapping = mapper.readValue(json, CsvColumnMapping.class);
        assertThat(mapping.getRequired(), is((Boolean) null));
        assertThat(mapping.isRequiredColumn(), is(true));
    }
}
