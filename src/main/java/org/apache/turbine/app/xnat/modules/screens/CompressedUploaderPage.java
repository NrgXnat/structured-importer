package org.apache.turbine.app.xnat.modules.screens;

import org.apache.turbine.util.RunData;
import org.apache.velocity.context.Context;
import org.nrg.xdat.XDAT;
import org.nrg.xdat.turbine.modules.screens.SecureScreen;
import org.nrg.xnatx.plugins.structimport.models.ModalityMapping;
import org.nrg.xnatx.plugins.structimport.services.ModalityDataTypeService;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.stream.Collectors;

@SuppressWarnings("unused")
public class CompressedUploaderPage extends SecureScreen {

    private static final SimpleDateFormat FORMATTER = new SimpleDateFormat("yyyyMMdd_hhmmss");

    @Override
    protected void doBuildTemplate(final RunData data, final Context context) throws Exception {
        context.put("uploadID", FORMATTER.format(Calendar.getInstance().getTime()));
        context.put("modalities", XDAT.getContextService()
                                      .getBean(ModalityDataTypeService.class)
                                      .getSessionModalities()
                                      .stream()
                                      .map(ModalityMapping::getModality)
                                      .collect(Collectors.toList()));
    }
}
