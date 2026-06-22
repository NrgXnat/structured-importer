package org.apache.turbine.app.xnat.modules.screens;

import org.apache.turbine.util.RunData;
import org.apache.velocity.context.Context;
import org.nrg.xdat.turbine.modules.screens.SecureScreen;
import org.nrg.xnatx.plugins.structimport.importer.StructuredImporter;

import java.text.SimpleDateFormat;
import java.util.Calendar;

@SuppressWarnings("unused")
public class CompressedUploaderPage extends SecureScreen {

    private static final SimpleDateFormat FORMATTER = new SimpleDateFormat("yyyyMMdd_hhmmss");

    @Override
    protected void doBuildTemplate(final RunData data, final Context context) throws Exception {
        context.put("uploadID", FORMATTER.format(Calendar.getInstance().getTime()));
        context.put("modalities", StructuredImporter.SUPPORTED_MODALITIES);
    }
}
