(ns oc.reminder.representations.user
  "Resource representations for OpenCompany users."
  (:require [cheshire.core :as json]
            [oc.lib.hateoas :as hateoas]
            [oc.reminder.config :as config]))

;; User media types
(def media-type "application/vnd.open-company.user.v1+json")
(def collection-media-type "application/vnd.collection+vnd.open-company.user+json;version=1")

(defn url [org-uuid]
  (str "/orgs/" org-uuid "/assignee-roster"))

(defn render-assignee-list
  "Create a JSON representation of a list of the org's reminder assignees for the API."
  [org-uuid users access]
  (let [links [(hateoas/self-link (url org-uuid) {:accept collection-media-type})]]
    (json/generate-string
      {:collection {:version hateoas/json-collection-version
                    :href (url org-uuid)
                    :links links
                    :items users}}
      {:pretty config/pretty?})))