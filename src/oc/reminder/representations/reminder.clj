(ns oc.reminder.representations.reminder
  "Resource representations for OpenCompany reminders."
  (:require [cheshire.core :as json]
            [oc.lib.hateoas :as hateoas]
            [oc.reminder.config :as config]))

;; Reminder media types
(def media-type "application/vnd.open-company.reminder.v1+json")
(def collection-media-type "application/vnd.collection+vnd.open-company.reminder+json;version=1")

(def representation-props [:uuid :headline :author :assignee :frequency :next-send :created-at :updated-at])

(defn- org-url [org-uuid]
  (str "/orgs/" org-uuid "/"))

(defn- url [reminder]
  (str (org-url (:org-uuid reminder)) (:uuid reminder) "/"))

(defn- create-link [org-uuid]
  (hateoas/create-link (org-url org-uuid) {:content-type media-type
                                           :accept media-type}))

(defn- item-link [reminder] (hateoas/item-link (url reminder) {:accept media-type}))

(defn- select-occurence [reminder-props reminder]
  (let [prop-name (if (#{:weekly :biweekly} (keyword (:frequency reminder))) :week-occurence :period-occurence)]
    (assoc reminder-props prop-name (prop-name reminder))))

(defn- render-reminder [reminder]
  (-> reminder
    (select-keys representation-props)
    (select-occurence reminder)))

(defn- reminder-collection-links [reminder]
  (assoc (render-reminder reminder) :links [(item-link reminder)]))

(defn render-reminder-list [org-uuid reminders access]
  (let [links [(hateoas/self-link (org-url org-uuid) {:accept collection-media-type})]
        full-links (if (= access :author)
                      (conj links (create-link org-uuid))
                      links)]
  (json/generate-string
    {:collection {:version hateoas/json-collection-version
                  :href (org-url org-uuid)
                  :links full-links
                  :items (map reminder-collection-links reminders)}}
    {:pretty config/pretty?})))