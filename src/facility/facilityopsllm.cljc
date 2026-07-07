(ns facility.facilityopsllm
  "FacilityOps-LLM client -- the *contained intelligence node* for the
  sports-facility actor.

  It normalizes facility intake, drafts a per-jurisdiction assembly-
  venue safety evidence checklist, screens facilities for a failed
  post-hold inspection, and drafts the facility-use-authorization
  action. CRITICAL: it is a smart-but-untrusted advisor. It returns a
  *proposal* (with a rationale + the fields it cited), never a
  committed record or a real authorization. Every output is censored
  downstream by `facility.governor` before anything touches the SSoT,
  and `:facility/authorize-use` proposals NEVER auto-commit at any
  phase -- see README `Actuation`.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis gate
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     kw             ; how a commit would mutate the SSoT
     :stake      kw|nil         ; :actuation/authorize-facility-use | nil
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [facility.facts :as facts]
            [facility.registry :as registry]
            [facility.store :as store]
            [langchain.model :as model]))

(defn- normalize-intake
  "Directory upsert -- the LLM only normalizes/validates the patch; it
  does not invent the facility, occupancy figures or jurisdiction.
  High confidence, low stakes."
  [_db {:keys [patch]}]
  {:summary    (str "施設記録更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :facility/upsert
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- assess-jurisdiction
  "Per-jurisdiction assembly-venue safety evidence checklist draft.
  `:no-spec?` injects the failure mode we must defend against:
  proposing a checklist for a jurisdiction with NO official spec-basis
  in `facility.facts` -- the Facility Safety Governor must reject this
  (never invent a jurisdiction's requirements)."
  [db {:keys [subject no-spec?]}]
  (let [f (store/facility db subject)
        iso3 (if no-spec? "ATL" (:jurisdiction f))
        sb (facts/spec-basis iso3)]
    (if (nil? sb)
      {:summary    (str iso3 " の公式spec-basisが見つかりません")
       :rationale  "facility.facts に未登録の法域。要件を推測で作らない。"
       :cites      []
       :effect     :assessment/set
       :value      {:jurisdiction iso3 :checklist [] :spec-basis nil}
       :stake      nil
       :confidence 0.9}
      {:summary    (str iso3 " (" (:owner-authority sb) ") 向け必要書類 "
                        (count (:required-evidence sb)) " 件を提案")
       :rationale  (str "公式ソース: " (:provenance sb) " / 法的根拠: " (:legal-basis sb))
       :cites      [(:legal-basis sb) (:provenance sb)]
       :effect     :assessment/set
       :value      {:jurisdiction iso3
                    :checklist (:required-evidence sb)
                    :spec-basis (:provenance sb)
                    :legal-basis (:legal-basis sb)}
       :stake      nil
       :confidence 0.9})))

(defn- screen-inspection
  "Post-hold inspection screening draft. `:current-occupancy`/
  `:maximum-capacity` on the facility record injects the failure mode
  independently recomputed by the governor; here we model a distinct
  post-hold physical-inspection outcome via `:inspection-passed?`."
  [db {:keys [subject]}]
  (let [f (store/facility db subject)]
    (cond
      (nil? f)
      {:summary "対象施設が見つかりません" :rationale "no facility record"
       :cites [] :effect :inspection-screening/set :value {:facility-id subject :verdict :unknown}
       :stake nil :confidence 0.0}

      (false? (:inspection-passed? f))
      {:summary    (str (:facility-name f) ": 安全点検の不合格を検出")
       :rationale  "スクリーニングが不合格の安全点検結果を検出。人手確認とホールドが必須。"
       :cites      [:safety-check]
       :effect     :inspection-screening/set
       :value      {:facility-id subject :verdict :failed}
       :stake      nil
       :confidence 0.95}

      :else
      {:summary    (str (:facility-name f) ": 安全点検合格")
       :rationale  "安全点検スクリーニング完了。"
       :cites      [:safety-check]
       :effect     :inspection-screening/set
       :value      {:facility-id subject :verdict :passed}
       :stake      nil
       :confidence 0.9})))

(defn- propose-facility-use-authorization
  "Draft the actual FACILITY-USE-AUTHORIZATION action -- authorizing
  real facility use during or after a flagged safety condition. ALWAYS
  `:stake :actuation/authorize-facility-use` -- this is a REAL-WORLD,
  public-safety-critical act, never a draft the actor may auto-run.
  See README `Actuation`: no phase ever adds this op to a phase's
  `:auto` set (`facility.phase`); the governor also always escalates
  on `:actuation/authorize-facility-use`. Two independent layers
  agree, deliberately."
  [db {:keys [subject]}]
  (let [f (store/facility db subject)
        over-capacity? (and f (registry/occupancy-exceeds-capacity? f))]
    {:summary    (str subject " 向け利用許可提案"
                      (when f (str " (facility=" (:facility-name f) ")")))
     :rationale  (if f
                   (str "current-occupancy=" (:current-occupancy f)
                        " maximum-capacity=" (:maximum-capacity f))
                   "施設が見つかりません")
     :cites      (if f [subject] [])
     :effect     :facility/mark-authorized
     :value      {:facility-id subject}
     :stake      :actuation/authorize-facility-use
     :confidence (if over-capacity? 0.3 0.9)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :facility/intake            (normalize-intake db request)
    :jurisdiction/assess             (assess-jurisdiction db request)
    :inspection/screen                   (screen-inspection db request)
    :facility/authorize-use                  (propose-facility-use-authorization db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたはスポーツ施設の利用許可エージェントの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。説明や前置きは"
       "一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:facility/upsert|:assessment/set|:inspection-screening/set|"
       ":facility/mark-authorized) "
       ":stake(:actuation/authorize-facility-use か nil) :confidence(0..1)。\n"
       "重要: 登録されていない法域の要件を絶対に創作してはいけません。"
       "spec-basisが無い場合は :cites を空にし confidence を上げないこと。"))

(defn- facts-for [st {:keys [op subject]}]
  (case op
    :jurisdiction/assess     {:facility (store/facility st subject)}
    :inspection/screen       {:facility (store/facility st subject)}
    :facility/authorize-use  {:facility (store/facility st subject)}
    {:facility (store/facility st subject)}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the Facility Safety Governor
  escalates/holds -- an LLM hiccup can never auto-authorize facility
  use."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :facilityopsllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
