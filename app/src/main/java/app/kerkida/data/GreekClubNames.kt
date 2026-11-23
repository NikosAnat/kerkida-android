package app.kerkida.data

import java.text.Normalizer
import java.util.Locale

/**
 * One Greek name for a club, whichever spelling or sponsor name a source uses.
 * Display only: ids and matching keep the source's name.
 */
object GreekClubNames {
    private val Clubs: List<Pair<String, List<String>>> = listOf(
        "Ολυμπιακός" to listOf("Olympiacos", "Olympiakos", "Olympiacos Piraeus", "Olympiacos BC", "ΟΛΥΜΠΙΑΚΟΣ"),
        "Ολυμπιακός Β" to listOf("Olympiacos II", "Olympiakos Piraeus II"),
        "Παναθηναϊκός" to listOf("Panathinaikos", "Panathinaikos Athens", "Panathinaikos BC", "ΠΑΝΑΘΗΝΑΪΚΟΣ AKTOR", "ΠΑΝΑΘΗΝΑΪΚΟΣ"),
        "ΑΕΚ" to listOf("AEK Athens", "AEK", "ΑΕΚ"),
        "ΠΑΟΚ" to listOf("PAOK", "PAOK Thessaloniki", "PAOK Thessaloniki VC", "PAOK BC Thessaloniki", "ΠΑΟΚ"),
        "ΠΑΟΚ Β" to listOf("PAOK II"),
        "Άρης" to listOf("Aris", "Aris Thessaloniki", "Aris Thessaloniki VC", "ΑΡΗΣ"),
        "Αστέρας Τρίπολης" to listOf("Asteras Tripoli", "Asteras Tripolis"),
        "Αστέρας Τρίπολης Β" to listOf("Asteras Tripolis II"),
        "Ατρόμητος" to listOf("Atromitos"),
        "Ηρακλής" to listOf("Iraklis", "Iraklis Thessaloniki", "ΗΡΑΚΛΗΣ"),
        "Καλαμάτα" to listOf("Kalamata"),
        "Κηφισιά" to listOf("Kifisia", "AOP Kifisias Athens"),
        "Λεβαδειακός" to listOf("Levadiakos"),
        "ΟΦΗ" to listOf("OFI", "OFI Crete", "OFI Crete VC"),
        "Παναιτωλικός" to listOf("Panetolikos"),
        "Βόλος" to listOf("Volos", "Volos NFC"),
        "ΑΕΛ" to listOf("AEL"),
        "Αναγέννηση Καρδίτσας" to listOf("Anagennisi Karditsas"),
        "Απόλλων Καλαμαριάς" to listOf("Apollon Kalamarias"),
        "Αθήνα Καλλιθέα" to listOf("Athens Kallithea"),
        "Ελλάς Σύρου" to listOf("Hellas Syros"),
        "Μαρκό" to listOf("Marko"),
        "Νέστος Χρυσούπολης" to listOf("Nestos Chrysoupoli"),
        "Νίκη Βόλου" to listOf("Niki Volos", "GS Niki Volou"),
        "Πανιώνιος" to listOf("Panionios", "Panionios Athens", "Panionios VC"),
        "Πανσερραϊκός" to listOf("Panserraikos"),
        "Πανθρακικός" to listOf("Panthrakikos"),
        "Πύργος" to listOf("Pyrgos"),
        "Ζάκυνθος" to listOf("Zakynthos"),
        "Παναχαϊκή" to listOf("Panachaiki"),
        "Παναιγιάλειος" to listOf("Panegialios"),
        "Ατρόμητος Πατρών" to listOf("Atromitos Patron"),
        "Παναγρινιακός" to listOf("Panagriniakos"),
        "Προμηθέας Πάτρας" to listOf("Promitheas Patras BC", "ΠΡΟΜΗΘΕΑΣ ΠΑΤΡΑΣ ΒΙΚΟΣ COLA", "ΠΡΟΜΗΘΕΑΣ"),
        "Περιστέρι" to listOf("Peristeri BC", "ΠΕΡΙΣΤΕΡΙ Betsson", "ΠΕΡΙΣΤΕΡΙ"),
        "Μαρούσι" to listOf("Maroussi Athens", "ΜΑΡΟΥΣΙ"),
        "Κολοσσός Ρόδου" to listOf("Kolossos Rhodes BC", "ΚΟΛΟΣΣΟΣ H HOTELS COLLECTION"),
        "Μύκονος" to listOf("Mykonos", "AO Mykonos BC", "ΜΥΚΟΝΟΣ Betsson BC"),
        "Καρδίτσα" to listOf("AS Karditsas", "ΚΑΡΔΙΤΣΑ ΙΑΠΩΝΙΚΗ"),
        "Δόξα Λευκάδας" to listOf("AE Doxa Lefkadas", "ΔΟΞΑ ΛΕΥΚΑΔΑΣ ELABET"),
        "Βίκος" to listOf("Vikos", "VIKOS ΦALCONS"),
        "Λαύριο" to listOf("GS Lavrio BC"),
        "Ψυχικό" to listOf("AE Psychikou"),
        "Παπάγου" to listOf("AS Papagou Athens"),
        "Πειραματικό Μαχητές" to listOf("Peiramatiko Machites"),
        "Κομοτηνή" to listOf("Komotini"),
        "Αμύντας" to listOf("Amyntas"),
        "Σοφάδες" to listOf("Sofadon"),
        "Πρωτέας Βούλας" to listOf("Proteas Voulas"),
        "Μεγαρίδα" to listOf("NE Megaridas"),
        "Χαλκίδα" to listOf("Chalkida"),
        "Κόροιβος Αμαλιάδας" to listOf("Koroivos Amaliadas BC"),
        "Πανερυθραϊκός" to listOf("Panerythraikos AS"),
        "Κρόνος Αγίου Δημητρίου" to listOf("Kronos Agiou Dimitriou"),
        "Έσπερος Καλλιθέας" to listOf("Esperos Kallitheas BC"),
        "Φλοίσβος" to listOf("AO Flisvos"),
        "Φοίνικας Σύρου" to listOf("Foinikas Syros"),
        "Μίλωνας" to listOf("AONS Milon Athens"),
        "Ρέθυμνο" to listOf("Rethymno"),
    )

    private fun key(text: String) = Normalizer.normalize(text.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }

    private val byKey: Map<String, String> = Clubs.flatMap { (greek, names) -> (names + greek).map { key(it) to greek } }.toMap()

    fun greek(name: String): String = byKey[key(name)] ?: name

    private val ExtraPlaces = mapOf(
        "Europe" to "Ευρώπη", "England" to "Αγγλία", "Scotland" to "Σκωτία", "Wales" to "Ουαλία",
        "USA" to "ΗΠΑ", "USA / Canada" to "ΗΠΑ / Καναδάς", "United States" to "ΗΠΑ", "World" to "Κόσμος",
        "International" to "Διεθνείς",
    )
    private val CountryCodes: Map<String, String> by lazy {
        Locale.getISOCountries().associateBy { Locale.Builder().setRegion(it).build().getDisplayCountry(Locale.ENGLISH) }
    }

    /** Sources name places in English. */
    fun place(name: String, locale: Locale): String {
        if (locale.language != "el") return name
        ExtraPlaces[name]?.let { return it }
        val code = CountryCodes[name] ?: return name
        return Locale.Builder().setRegion(code).build().getDisplayCountry(locale).ifBlank { name }
    }
}
