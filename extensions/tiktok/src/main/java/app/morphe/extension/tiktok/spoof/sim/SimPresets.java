package app.morphe.extension.tiktok.spoof.sim;

import androidx.annotation.Nullable;

public class SimPresets {
    public static final SimPreset[] PRESETS = {
            new SimPreset("United States", "us", "310260", "T-Mobile"),
            new SimPreset("United Kingdom", "gb", "23430", "EE"),
            new SimPreset("Canada", "ca", "302720", "Rogers"),
            new SimPreset("Russia", "ru", "25001", "MTS"),
            new SimPreset("Germany", "de", "26201", "Telekom.de"),
            new SimPreset("France", "fr", "20801", "Orange"),
            new SimPreset("Italy", "it", "22201", "TIM"),
            new SimPreset("Spain", "es", "21407", "Movistar"),
            new SimPreset("Netherlands", "nl", "20408", "KPN"),
            new SimPreset("Poland", "pl", "26003", "Orange"),
            new SimPreset("Portugal", "pt", "26806", "MEO"),
            new SimPreset("Belgium", "be", "20601", "Proximus"),
            new SimPreset("Switzerland", "ch", "22801", "Swisscom"),
            new SimPreset("Austria", "at", "23201", "A1"),
            new SimPreset("Sweden", "se", "24001", "Telia"),
            new SimPreset("Norway", "no", "24201", "Telenor"),
            new SimPreset("Denmark", "dk", "23801", "TDC"),
            new SimPreset("Greece", "gr", "20201", "Cosmote"),
            new SimPreset("Ukraine", "ua", "25503", "Kyivstar"),
            new SimPreset("Romania", "ro", "22610", "Orange"),
            new SimPreset("Czech Republic", "cz", "23001", "T-Mobile"),
            new SimPreset("Hungary", "hu", "21630", "Magyar Telekom"),
            new SimPreset("Ireland", "ie", "27201", "Vodafone"),
            new SimPreset("Turkey", "tr", "28601", "Turkcell"),
            new SimPreset("United Arab Emirates", "ae", "42403", "du"),
            new SimPreset("United Arab Emirates", "ae", "42402", "Etisalat"),
            new SimPreset("Saudi Arabia", "sa", "42001", "stc"),
            new SimPreset("Qatar", "qa", "42701", "Ooredoo"),
            new SimPreset("Kuwait", "kw", "41902", "Zain"),
            new SimPreset("Oman", "om", "42202", "Omantel"),
            new SimPreset("Jordan", "jo", "41601", "Zain"),
            new SimPreset("Iraq", "iq", "41820", "Zain"),
            new SimPreset("Lebanon", "lb", "41501", "Alfa"),
            new SimPreset("Egypt", "eg", "60202", "Vodafone"),
            new SimPreset("Morocco", "ma", "60401", "Maroc Telecom"),
            new SimPreset("Algeria", "dz", "60301", "Mobilis"),
            new SimPreset("Tunisia", "tn", "60502", "Tunisie Telecom"),
            new SimPreset("India", "in", "405840", "Jio"),
            new SimPreset("Pakistan", "pk", "41001", "Jazz"),
            new SimPreset("Bangladesh", "bd", "47001", "Grameenphone"),
            new SimPreset("Sri Lanka", "lk", "41302", "Dialog"),
            new SimPreset("Nepal", "np", "42902", "Ncell"),
            new SimPreset("Indonesia", "id", "51010", "Telkomsel"),
            new SimPreset("Philippines", "ph", "51503", "Smart"),
            new SimPreset("Thailand", "th", "52003", "AIS"),
            new SimPreset("Vietnam", "vn", "45204", "Viettel"),
            new SimPreset("Malaysia", "my", "50212", "Maxis"),
            new SimPreset("Singapore", "sg", "52501", "Singtel"),
            new SimPreset("Hong Kong", "hk", "45400", "CSL"),
            new SimPreset("Taiwan", "tw", "46692", "Chunghwa Telecom"),
            new SimPreset("Japan", "jp", "44010", "NTT DOCOMO"),
            new SimPreset("South Korea", "kr", "45005", "SK Telecom"),
            new SimPreset("Australia", "au", "50501", "Telstra"),
            new SimPreset("New Zealand", "nz", "53005", "Spark"),
            new SimPreset("Brazil", "br", "72410", "Vivo"),
            new SimPreset("Mexico", "mx", "334020", "Telcel"),
            new SimPreset("Argentina", "ar", "722310", "Claro"),
            new SimPreset("Colombia", "co", "732101", "Claro"),
            new SimPreset("Chile", "cl", "73001", "Entel"),
            new SimPreset("Peru", "pe", "71610", "Claro"),
            new SimPreset("South Africa", "za", "65501", "Vodacom"),
            new SimPreset("Nigeria", "ng", "62130", "MTN"),
            new SimPreset("Kenya", "ke", "63902", "Safaricom"),
            new SimPreset("Ghana", "gh", "62001", "MTN"),
            new SimPreset("Ethiopia", "et", "63601", "Ethio Telecom")
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
        return iso.trim().isEmpty()
                && mccMnc.trim().isEmpty()
                && operatorName.trim().isEmpty();
    }
}
