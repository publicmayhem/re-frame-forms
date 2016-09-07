(ns reframe-forms.core
  (:require
    #_[om.core :as om :include-macros true]
    [sablono.core :as sab :include-macros true]
    [reagent.core :as reagent]
    [struct.core :as st]
    [cuerdas.core :as str])
  (:require-macros
    [devcards.core :as dc :refer [defcard deftest defcard-rg]]
    [reagent.ratom :refer [reaction]]
    ))

(enable-console-print!)


(defprotocol Value
  (value [this]
         [this default])
  (set-value! [this val]))

(defprotocol ResetValue
  (original-value [this])
  (reset-value! [this]))

(defprotocol Coercer
  (to-str [this obj-value])
  (from-str [this str-value]))

(defprotocol PathValue
  (path-value [this type path]
              [this type path default]))

(defprotocol CoercedValue
  (str-value [this])
  (set-str-value! [this val]))

(defprotocol ErrorContainer
  (errors [this]))

(defprotocol Validatable
  (valid? [this]))

(defprotocol Validator
  (validate [this value]))

Validator by mohol vracat nejaky Errors kde bude povedane ci je ok a path

(defprotocol Touchable
  (touch [this])
  (touched? [this]))

(defn- field-path [type path]
  (if (#{::value ::original ::validator-errors} type)
    (cons type path)
    [type path]))

(defn- assoc-field [form path & kvs]
  (reduce (fn [form [type value]]
            (assoc-in form (field-path type path) value))
          form
          (partition 2 kvs)))

(defrecord Field [form coercer validator path]
  Value
  (value [this]
    (value this nil))
  (value [_ default]
    (path-value form ::value path default))
  (set-value! [_ val]
    (swap! form assoc-field path
           ::field-errors (validate validator val)
           ::value val
           ::tmp nil
           ::touched true))

  ResetValue
  (original-value [this]
    @(path-value form ::original path nil))
  (reset-value! [this]
    (swap! form assoc-field path
           ::field-errors []
           ::value (original-value this)
           ::tmp nil
           ::touched false))

  CoercedValue
  (str-value [this]
    (reaction (or @(path-value form ::tmp path nil)
                  (->> @(value this)
                       (to-str coercer)))))
  (set-str-value! [this val]
    (let [errors (validate coercer val)]
      (if (empty? errors)
        (->> (from-str coercer val)
             (set-value! this))
        (swap! form assoc-field path
               ::field-errors errors
               ::value nil
               ::tmp val
               ::touched true))))

  ErrorContainer
  (errors [this]
    (reaction (->> (concat @(path-value form ::field-errors path nil)
                           [@(path-value form ::validator-errors path)])
                   (remove empty?))))
  Validatable
  (valid? [this]
    (reaction (empty? @(errors this))))

  Touchable
  (touch [this]
    (swap! form assoc-field path
           ::touched true))
  (touched? [_]
    (reaction @(path-value form ::touched path false))))

(defmulti coercer identity)
(defmethod coercer :default
  [_] (reify
        Coercer
        (to-str [_ obj] (str obj))
        (from-str [_ s] s)

        Validator
        (validate [_ s])))

(defmethod coercer :int
  [_] (reify
        Coercer
        (to-str [_ obj] (str obj))
        (from-str [_ s] (if (str/empty? s) nil (js/parseInt s)))

        Validator
        (validate [_ s]
          (if (or (str/empty? s) (re-matches #"(\+|\-)?\d+" s))
            []
            ["Neplatné číslo"]
            ))))


(defn- validate-form
  ([f]
   (fn [value & args]
     (validate-form value f args)))
  ([value f & args]
   (let [validator (::validator value)
         new-value (apply f value args)]
     (-> new-value
         (assoc ::validator-errors (validator (::value new-value)))))))

(defrecord Form [value]
  ISwap
  (-swap! [o f]
    (swap! value validate-form f))
  (-swap! [o f a]
    (swap! value validate-form f a))
  (-swap! [o f a b]
    (swap! value validate-form f a b))
  (-swap! [o f a b xs]
    (apply swap! value validate-form f a b xs))

  Value
  (value [this]
    (value this nil))
  (value [_ default]
    (reaction (get @value ::value default)))
  (set-value! [this val]
    (swap! value
           validate-form assoc ::value val))

  ResetValue
  (original-value [this]
    (get @value ::original))
  (reset-value! [this]
    (swap! value (fn [value]
                   {::value    (::original value)
                    ::original (::original value)}
                   ::validator (::validator value))))

  PathValue
  (path-value [_ type path]
    (path-value _ type path nil))
  (path-value [_ type path default]
    (reaction (get-in @value (field-path type path) default)))

  Validatable
  (valid? [_]
    (reaction (and
                (->> (::field-errors @value)
                     vals
                     (remove empty?)
                     empty?)
                (empty? (::validator-errors @value))))))

(defn field [form type path]
  (->Field form (coercer type) (reify Validator
                                 (validate [_ value] [])) path))

(defn create-form [value validator]
  (->Form (reagent/atom {::value     value
                         ::original  value
                         ::validator #(-> %
                                          (st/validate validator)
                                          first)})))

(defn handle-str-value [field]
  #(set-str-value! field (-> % .-target .-value)))

(defcard first-card
  (sab/html [:div
             [:h1 "This is your first devcard!"]]))


(defn my-field [type field]
  (fn [type field]
    [:div
     [:input {:type      type
              :value     @(str-value field)
              :on-change (handle-str-value field)}]
     [:button {:type     "button"
               :on-click #(reset-value! field)} "Reset"]
     [:span (original-value field)]
     (when @(touched? field) "touched")
     (for [[i message] (map-indexed vector @(errors field))]
       ^{:key i} [:li message])]))

(defcard-rg rg-form
  (fn [state]
    (let [form      (create-form
                      @state
                      {:field     [st/required st/string]
                       :int-field [st/required st/integer]})
          int-field (field form :int [:int-field])]
      (fn [state]
        [:form {:on-submit #(do
                             (prn "submit" @(value form {}) @state)
                             (reset! state @(value form {}))
                             (.preventDefault %))}
         [:div "Valid?:" (if @(valid? form) "T" "F")]
         [my-field "text" (field form :text [:field])]
         [my-field "text" (field form :int [:int-field])]
         [:select
          {:value     @(str-value int-field)
           :on-change (handle-str-value int-field)}
          [:option {:value 1} "1"]
          [:option {:value 2} "2"]]
         [:input {:type "submit"}]
         (.log js/console @(valid? form) (clj->js @(:value form)))
         ])))
  {:field     "value"
   :int-field 1}

  {:inspect-data true}
  )

(defn main []
  ;; conditionally start the app based on whether the #main-app-area
  ;; node is on the page
  (if-let [node (.getElementById js/document "main-app-area")]
    (js/React.render (sab/html [:div "This is working"]) node)))

(main)

;; remember to run lein figwheel and then browse to
;; http://localhost:3449/cards.html

