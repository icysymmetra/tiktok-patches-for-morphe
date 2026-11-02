/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.spoof.sim;

import androidx.annotation.Nullable;

public class SimPresets {
    public static final SimPreset[] PRESETS = {
            new SimPreset("United States", "us", "310260", "T-Mobile", "America/New_York"),
            new SimPreset("United Kingdom", "gb", "23430", "EE", "Europe/London"),
            new SimPreset("Canada", "ca", "302720", "Rogers", "America/Toronto"),
            new SimPreset("Russia", "ru", "25001", "MTS", "Europe/Moscow"),
            new SimPreset("Germany", "de", "26201", "Telekom.de", "Europe/Berlin"),
            new SimPreset("France", "fr", "20801", "Orange", "Europe/Paris"),
            new SimPreset("Italy", "it", "22201", "TIM", "Europe/Rome"),
            new SimPreset("Spain", "es", "21407", "Movistar", "Europe/Madrid"),
            new SimPreset("Netherlands", "nl", "20408", "KPN", "Europe/Amsterdam"),
            new SimPreset("Poland", "pl", "26003", "Orange", "Europe/Warsaw"),
            new SimPreset("Portugal", "pt", "26806", "MEO", "Europe/Lisbon"),
            new SimPreset("Belgium", "be", "20601", "Proximus", "Europe/Brussels"),
            new SimPreset("Switzerland", "ch", "22801", "Swisscom", "Europe/Zurich"),
            new SimPreset("Austria", "at", "23201", "A1", "Europe/Vienna"),
            new SimPreset("Sweden", "se", "24001", "Telia", "Europe/Stockholm"),
            new SimPreset("Norway", "no", "24201", "Telenor", "Europe/Oslo"),
            new SimPreset("Denmark", "dk", "23801", "TDC", "Europe/Copenhagen"),
            new SimPreset("Greece", "gr", "20201", "Cosmote", "Europe/Athens"),
            new SimPreset("Bulgaria", "bg", "28401", "A1 BG", "Europe/Sofia"),
            new SimPreset("Serbia", "rs", "22003", "mt:s", "Europe/Belgrade"),
            new SimPreset("Croatia", "hr", "21901", "HT HR", "Europe/Zagreb"),
            new SimPreset("Bosnia and Herzegovina", "ba", "21890", "BH Mobile", "Europe/Sarajevo"),
            new SimPreset("Slovenia", "si", "29341", "Mobitel", "Europe/Ljubljana"),
            new SimPreset("Montenegro", "me", "29702", "telekom.me", "Europe/Podgorica"),
            new SimPreset("North Macedonia", "mk", "29401", "Telekom.mk", "Europe/Skopje"),
            new SimPreset("Albania", "al", "27601", "ONE", "Europe/Tirane"),
            new SimPreset("Ukraine", "ua", "25503", "Kyivstar", "Europe/Kiev"),
            new SimPreset("Romania", "ro", "22610", "Orange", "Europe/Bucharest"),
            new SimPreset("Czech Republic", "cz", "23001", "T-Mobile", "Europe/Prague"),
            new SimPreset("Hungary", "hu", "21630", "Magyar Telekom", "Europe/Budapest"),
            new SimPreset("Ireland", "ie", "27201", "Vodafone", "Europe/Dublin"),
            new SimPreset("Turkey", "tr", "28601", "Turkcell", "Europe/Istanbul"),
            new SimPreset("United Arab Emirates", "ae", "42402", "Etisalat", "Asia/Dubai"),
            new SimPreset("Saudi Arabia", "sa", "42001", "stc", "Asia/Riyadh"),
            new SimPreset("Qatar", "qa", "42701", "Ooredoo", "Asia/Qatar"),
            new SimPreset("Kuwait", "kw", "41902", "Zain", "Asia/Kuwait"),
            new SimPreset("Oman", "om", "42202", "Omantel", "Asia/Muscat"),
            new SimPreset("Jordan", "jo", "41601", "Zain", "Asia/Amman"),
            new SimPreset("Iraq", "iq", "41820", "Zain", "Asia/Baghdad"),
            new SimPreset("Lebanon", "lb", "41501", "Alfa", "Asia/Beirut"),
            new SimPreset("Egypt", "eg", "60202", "Vodafone", "Africa/Cairo"),
            new SimPreset("Morocco", "ma", "60401", "Maroc Telecom", "Africa/Casablanca"),
            new SimPreset("Algeria", "dz", "60301", "Mobilis", "Africa/Algiers"),
            new SimPreset("Tunisia", "tn", "60502", "Tunisie Telecom", "Africa/Tunis"),
            new SimPreset("India", "in", "405840", "Jio", "Asia/Kolkata"),
            new SimPreset("Pakistan", "pk", "41001", "Jazz", "Asia/Karachi"),
            new SimPreset("Bangladesh", "bd", "47001", "Grameenphone", "Asia/Dhaka"),
            new SimPreset("Sri Lanka", "lk", "41302", "Dialog", "Asia/Colombo"),
            new SimPreset("Nepal", "np", "42902", "Ncell", "Asia/Kathmandu"),
            new SimPreset("Indonesia", "id", "51010", "Telkomsel", "Asia/Jakarta"),
            new SimPreset("Philippines", "ph", "51503", "Smart", "Asia/Manila"),
            new SimPreset("Thailand", "th", "52003", "AIS", "Asia/Bangkok"),
            new SimPreset("Vietnam", "vn", "45204", "Viettel", "Asia/Ho_Chi_Minh"),
            new SimPreset("Malaysia", "my", "50212", "Maxis", "Asia/Kuala_Lumpur"),
            new SimPreset("Singapore", "sg", "52501", "Singtel", "Asia/Singapore"),
            new SimPreset("Hong Kong", "hk", "45400", "CSL", "Asia/Hong_Kong"),
            new SimPreset("Taiwan", "tw", "46692", "Chunghwa Telecom", "Asia/Taipei"),
            new SimPreset("Japan", "jp", "44010", "NTT DOCOMO", "Asia/Tokyo"),
            new SimPreset("South Korea", "kr", "45005", "SK Telecom", "Asia/Seoul"),
            new SimPreset("Australia", "au", "50501", "Telstra", "Australia/Sydney"),
            new SimPreset("New Zealand", "nz", "53005", "Spark", "Pacific/Auckland"),
            new SimPreset("Brazil", "br", "72410", "Vivo", "America/Sao_Paulo"),
            new SimPreset("Mexico", "mx", "334020", "Telcel", "America/Mexico_City"),
            new SimPreset("Argentina", "ar", "722310", "Claro", "America/Argentina/Buenos_Aires"),
            new SimPreset("Colombia", "co", "732101", "Claro", "America/Bogota"),
            new SimPreset("Chile", "cl", "73001", "Entel", "America/Santiago"),
            new SimPreset("Peru", "pe", "71610", "Claro", "America/Lima"),
            new SimPreset("South Africa", "za", "65501", "Vodacom", "Africa/Johannesburg"),
            new SimPreset("Nigeria", "ng", "62130", "MTN", "Africa/Lagos"),
            new SimPreset("Kenya", "ke", "63902", "Safaricom", "Africa/Nairobi"),
            new SimPreset("Ghana", "gh", "62001", "MTN", "Africa/Accra"),
            new SimPreset("Ethiopia", "et", "63601", "Ethio Telecom", "Africa/Addis_Ababa")
    };

    @Nullable
    public static SimPreset findSelected(String iso, String mccMnc, String operatorName) {
        for (SimPreset preset : PRESETS) {
            if (preset.hasSameValues(iso, mccMnc, operatorName)) {
                return preset;
            }
        }

        return null;
    }

    public static boolean hasEmptyCurrentValues(String iso, String mccMnc, String operatorName) {
        return trimOrEmpty(iso).isEmpty()
                && trimOrEmpty(mccMnc).isEmpty()
                && trimOrEmpty(operatorName).isEmpty();
    }

    private static String trimOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
