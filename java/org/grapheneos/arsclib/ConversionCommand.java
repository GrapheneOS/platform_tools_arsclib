package org.grapheneos.arsclib;

import java.util.ArrayList;
import java.util.List;

public class ConversionCommand {
    String unpackedOsImageDir;

    List<ApkConverter.SyntheticOverlaySpec> syntheticOverlays = new ArrayList<>();

    Filters pkgExclusionFilters;
    Filters exclusionFilters = new Filters();
    Filters inclusionFilters = new Filters();
    String outDir;
    String outModuleListPath;
}
