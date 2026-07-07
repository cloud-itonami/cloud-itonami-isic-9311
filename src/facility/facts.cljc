(ns facility.facts
  "Per-jurisdiction sports-facility/assembly-venue safety regulatory
  catalog -- the G2-style spec-basis table the Facility Safety
  Governor checks every jurisdiction/assess proposal against ('did the
  advisor cite an OFFICIAL public source for this jurisdiction's
  facility-safety/occupancy requirements, or did it invent one?').

  Coverage is reported HONESTLY (see `coverage`), the same discipline
  every sibling actor's `facts` namespace uses: a jurisdiction not in
  this table has NO spec-basis, full stop -- the advisor must not
  fabricate one, and the governor holds if it tries.

  Seed values are drawn from each jurisdiction's official fire-safety/
  assembly-venue regulator (see `:provenance`); they are a STARTING
  catalog, not a from-scratch survey of all ~194 jurisdictions.
  Extending coverage is additive: add one map to `catalog`, cite a
  real source, done -- never invent a jurisdiction's requirements to
  make coverage look bigger.

  The GBR entry cites the Sports Grounds Safety Authority (SGSA), the
  REAL UK statutory body specifically responsible for sports-ground
  safety certificates under the Safety of Sports Grounds Act 1975 --
  the most domain-specific citation available for this vertical, not
  a generic health-and-safety regulator. The USA entry cites the
  National Fire Protection Association (NFPA), a private standards
  body whose NFPA 101 Life Safety Code occupancy/means-of-egress
  provisions are the de facto basis most US jurisdictions adopt by
  reference for assembly-occupancy limits (the US has no single
  federal occupancy regulator) -- the same honest representative-
  citation posture every prior federated-jurisdiction catalog in this
  fleet takes.")

(def catalog
  "iso3 -> requirement map. `:required-evidence` mirrors the generic
  safety-inspection/occupancy-certification/egress-plan/insurance
  evidence set submitted in some form; `:legal-basis` / `:owner-
  authority` / `:provenance` are the G2 citation the governor requires
  before any :jurisdiction/assess proposal can commit."
  {"JPN" {:name "Japan"
          :owner-authority "総務省消防庁 (Fire and Disaster Management Agency, FDMA)"
          :legal-basis "消防法 (Fire Service Act)"
          :national-spec "特定防火対象物の収容人員・防火管理基準"
          :provenance "https://www.fdma.go.jp/"
          :required-evidence ["安全点検報告書 (safety-inspection report)"
                              "収容人員/定員証明書 (occupancy/capacity certification)"
                              "避難計画書 (emergency-egress plan)"
                              "保険/賠償責任証明書 (insurance/liability documentation)"]}
   "USA" {:name "United States"
          :owner-authority "National Fire Protection Association (NFPA)"
          :legal-basis "NFPA 101 Life Safety Code (Assembly Occupancy, Means of Egress)"
          :national-spec "Assembly-occupancy capacity and egress requirements"
          :provenance "https://www.nfpa.org/codes-and-standards/nfpa-101-standard-development/101"
          :required-evidence ["Safety-inspection report"
                              "Occupancy/capacity certification"
                              "Emergency-egress plan"
                              "Insurance/liability documentation"]}
   "GBR" {:name "United Kingdom"
          :owner-authority "Sports Grounds Safety Authority (SGSA)"
          :legal-basis "Safety of Sports Grounds Act 1975"
          :national-spec "General Safety Certificate capacity/egress conditions"
          :provenance "https://sgsa.org.uk/"
          :required-evidence ["Safety-inspection report"
                              "Occupancy/capacity certification"
                              "Emergency-egress plan"
                              "Insurance/liability documentation"]}
   "DEU" {:name "Germany"
          :owner-authority "Bauaufsichtsbehörden der Länder (state building supervisory authorities)"
          :legal-basis "Versammlungsstättenverordnung (VStättVO)"
          :national-spec "Höchstbesucherzahl- und Rettungswegvorgaben für Versammlungsstätten"
          :provenance "https://www.bauministerkonferenz.de/"
          :required-evidence ["Sicherheitsprüfbericht (safety-inspection report)"
                              "Besucherzahl-/Kapazitätsnachweis (occupancy/capacity certification)"
                              "Rettungswegplan (emergency-egress plan)"
                              "Versicherungs-/Haftungsnachweis (insurance/liability documentation)"]}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any proposal that tries to authorize
  facility use on it."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions actually
  have a spec-basis entry. Never report a missing jurisdiction as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-9311 R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `facility.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements.")})))

(defn required-evidence-satisfied?
  "Does `submitted` (a set/coll of evidence keywords or strings) satisfy
  every evidence item listed for `iso3`? Missing spec-basis -> never
  satisfied."
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))

(defn evidence-checklist [iso3]
  (:required-evidence (spec-basis iso3) []))
