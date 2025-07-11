package org.grapheneos.arsclib;

public class ConversionCommand {
    String unpackedOsImageDir;

    Filters pkgExclusionFilters;
    Filters exclusionFilters = new Filters();
    Filters inclusionFilters = new Filters();
    String outDir;
    String outModuleListPath;
}
