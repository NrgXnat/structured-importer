package org.nrg.xnatx.plugins.structimport.importer;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.nrg.action.ClientException;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

public class LabelResolverTest {

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Test
    public void parameterOnlyUsesParameter() throws Exception {
        assertThat(LabelResolver.resolve("subject", "SUBJ_FORM", null), equalTo("SUBJ_FORM"));
    }

    @Test
    public void resourceOnlyUsesResource() throws Exception {
        assertThat(LabelResolver.resolve("subject", " ", "SUBJ_CSV"), equalTo("SUBJ_CSV"));
    }

    @Test
    public void parameterOverridesResource() throws Exception {
        assertThat(LabelResolver.resolve("subject", "SUBJ_FORM", "SUBJ_CSV"), equalTo("SUBJ_FORM"));
    }

    @Test
    public void neitherThrows() throws Exception {
        thrown.expect(ClientException.class);
        thrown.expectMessage(containsString("Required session"));
        LabelResolver.resolve("session", null, "");
    }
}
