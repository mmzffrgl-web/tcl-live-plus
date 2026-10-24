package io.tcllive.overlay;

/** One tuner channel as shown in the overlay. */
final class Channel {
    /** Number shown in our list (sequential, gap-free). */
    String number;
    /** Real TCL displayChannelNumber used for tuning. */
    String tune;
    String name;
    /** XMLTV channel id, e.g. "TRT.1.HD.tr". May be null. */
    String epgId;
    /** Asset file name (without extension) under assets/logos/. May be null. */
    String logo;
    /** TIF/TvProvider channel _id, used for satellite (EIT) EPG lookup. May be null. */
    String tifId;

    Channel(String number, String name, String epgId, String logo) {
        this.number = number;
        this.tune = number;
        this.name = name;
        this.epgId = epgId;
        this.logo = logo;
    }

    Channel(String number, String name, String epgId, String logo, String tune) {
        this.number = number;
        this.tune = tune == null || tune.isEmpty() ? number : tune;
        this.name = name;
        this.epgId = epgId;
        this.logo = logo;
    }

    /** TCL'e gonderilecek gercek kanal numarasi. */
    String tuneNumber() {
        return tune == null || tune.isEmpty() ? number : tune;
    }

    String key() {
        return number + "|" + name;
    }
}
