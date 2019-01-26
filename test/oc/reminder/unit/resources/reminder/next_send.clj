(ns oc.reminder.unit.resources.reminder.next-send
  (:require [defun.core :refer (defun-)]
            [midje.sweet :refer :all]
            [java-time :as jt]
            [oc.reminder.resources.reminder :as reminder]))

;; ----- Test Data -----

(def EST "America/New_York") ; UTC -
(def CST "America/Chicago") ; UTC --
(def UTC "Europe/London") ; UTC on the nose
(def UTCplus "Australia/West") ; UTC +8
(def half-hour "Asia/Kabul") ; UTC +4:30

;; ----- Utilities -----

(defn- verify [y m d tz]
  (jt/format reminder/iso-format 
    (jt/with-zone-same-instant (jt/with-zone (jt/zoned-date-time y m d 9 0 0) tz) "UTC")))

(defn- reminder-for [cur-y cur-mo cur-d cur-h cur-mi frequency occurrence assignee-tz]
  (let [initial-local (jt/with-zone (jt/zoned-date-time cur-y cur-mo cur-d cur-h cur-mi) assignee-tz)
        initial-utc (jt/with-zone-same-instant initial-local UTC)
        initial-iso (jt/format reminder/iso-format initial-utc)]
    (#'reminder/next-reminder-for {
      :frequency frequency
      :week-occurrence occurrence
      :period-occurrence occurrence
      :assignee-timezone assignee-tz
      :next-send initial-iso})))

(defun- reminder-chain-for
  ([n cur-y cur-mo cur-d cur-h cur-mi frequency occurrence assignee-tz]
  (reminder-chain-for (reminder-for cur-y cur-mo cur-d cur-h cur-mi frequency occurrence assignee-tz) (dec n)))
  
  ([reminder 0] reminder)

  ([reminder n] (reminder-chain-for (reminder/next-reminder-for reminder) (dec n))))

;; ----- Tests -----

(facts "About reminder date/time calculations"

  (facts "About time for next reminder"

    (tabular
      (facts "About weekly reminders"
    
        (:next-send (reminder-for ?cur-y ?cur-mo ?cur-d ?cur-h ?cur-mi ?frequency ?occurrence ?assignee-tz)) =>
          (verify ?reminder-y ?reminder-mo ?reminder-d ?assignee-tz))

?assignee-tz ?frequency ?occurrence  ?cur-y ?cur-mo ?cur-d ?cur-h ?cur-mi ?reminder-y ?reminder-mo ?reminder-d
;; Weekly reminders before 9AM
EST          :weekly    :monday      2019   1       1      8      59      2019        1            7
CST          :weekly    :tuesday     2019   1       1      8      59      2019        1            1
UTC          :weekly    :wednesday   2019   1       1      8      59      2019        1            2
UTCplus      :weekly    :thursday    2019   1       1      8      59      2019        1            3
half-hour    :weekly    :friday      2019   1       1      8      59      2019        1            4
EST          :weekly    :saturday    2019   1       1      8      59      2019        1            5
CST          :weekly    :sunday      2019   1       1      8      59      2019        1            6
;; Weekly reminders at 9AM
EST          :weekly    :monday      2019   1       1      9      0       2019        1            7
CST          :weekly    :tuesday     2019   1       1      9      0       2019        1            8
UTC          :weekly    :wednesday   2019   1       1      9      0       2019        1            2
UTCplus      :weekly    :thursday    2019   1       1      9      0       2019        1            3
half-hour    :weekly    :friday      2019   1       1      9      0       2019        1            4
EST          :weekly    :saturday    2019   1       1      9      0       2019        1            5
CST          :weekly    :sunday      2019   1       1      9      0       2019        1            6
;; Weekly reminders after 9AM
EST          :weekly    :monday      2019   1       1      9      1       2019        1            7
CST          :weekly    :tuesday     2019   1       1      9      1       2019        1            8
UTC          :weekly    :wednesday   2019   1       1      9      1       2019        1            2
UTCplus      :weekly    :thursday    2019   1       1      9      1       2019        1            3
half-hour    :weekly    :friday      2019   1       1      9      1       2019        1            4
EST          :weekly    :saturday    2019   1       1      9      1       2019        1            5
CST          :weekly    :sunday      2019   1       1      9      1       2019        1            6
;; Weekly reminders spanning a month
EST          :weekly    :monday      2019   1       31     9      1       2019        2            4
CST          :weekly    :tuesday     2019   1       31     9      1       2019        2            5
UTC          :weekly    :wednesday   2019   1       31     9      1       2019        2            6
UTCplus      :weekly    :thursday    2019   1       31     8      59      2019        1            31
UTCplus      :weekly    :thursday    2019   1       31     9      1       2019        2            7
half-hour    :weekly    :friday      2019   1       31     9      1       2019        2            1
EST          :weekly    :saturday    2019   1       31     9      1       2019        2            2
CST          :weekly    :sunday      2019   1       31     9      1       2019        2            3
;; Weekly reminders spanning a year
EST          :weekly    :monday      2018   12      31     8      59      2018        12           31
EST          :weekly    :monday      2018   12      31     9      1       2019        1            7
CST          :weekly    :tuesday     2018   12      31     9      1       2019        1            1
UTC          :weekly    :wednesday   2018   12      31     9      1       2019        1            2
UTCplus      :weekly    :thursday    2018   12      31     9      1       2019        1            3
half-hour    :weekly    :friday      2018   12      31     9      1       2019        1            4
EST          :weekly    :saturday    2018   12      31     9      1       2019        1            5
CST          :weekly    :sunday      2018   12      31     9      1       2019        1            6)

  (tabular
    (facts "About bi-weekly reminders"
  
      (:next-send (reminder-for ?cur-y ?cur-mo ?cur-d ?cur-h ?cur-mi ?frequency ?occurrence ?assignee-tz)) =>
        (verify ?reminder-y ?reminder-mo ?reminder-d ?assignee-tz))

?assignee-tz ?frequency ?occurrence  ?cur-y ?cur-mo ?cur-d ?cur-h ?cur-mi ?reminder-y ?reminder-mo ?reminder-d
;; Biweekly reminders before 9AM
EST          :biweekly  :monday      2019   1       1      8      59      2019        1            14
CST          :biweekly  :tuesday     2019   1       1      8      59      2019        1            8
UTC          :biweekly  :wednesday   2019   1       1      8      59      2019        1            9
UTCplus      :biweekly  :thursday    2019   1       1      8      59      2019        1            10
half-hour    :biweekly  :friday      2019   1       1      8      59      2019        1            11
EST          :biweekly  :saturday    2019   1       1      8      59      2019        1            12
CST          :biweekly  :sunday      2019   1       1      8      59      2019        1            13
;; Biweekly reminders at 9AM
EST          :biweekly  :monday      2019   1       1      9      0       2019        1            14
CST          :biweekly  :tuesday     2019   1       1      9      0       2019        1            15
UTC          :biweekly  :wednesday   2019   1       1      9      0       2019        1            9
UTCplus      :biweekly  :thursday    2019   1       1      9      0       2019        1            10
half-hour    :biweekly  :friday      2019   1       1      9      0       2019        1            11
EST          :biweekly  :saturday    2019   1       1      9      0       2019        1            12
CST          :biweekly  :sunday      2019   1       1      9      0       2019        1            13
;; Biweekly reminders after 9AM
EST          :biweekly  :monday      2019   1       1      9      1       2019        1            14
CST          :biweekly  :tuesday     2019   1       1      9      1       2019        1            15
UTC          :biweekly  :wednesday   2019   1       1      9      1       2019        1            9
UTCplus      :biweekly  :thursday    2019   1       1      9      1       2019        1            10
half-hour    :biweekly  :friday      2019   1       1      9      1       2019        1            11
EST          :biweekly  :saturday    2019   1       1      9      1       2019        1            12
CST          :biweekly  :sunday      2019   1       1      9      1       2019        1            13
;; Biweekly reminders spanning a month
EST          :biweekly  :monday      2019   1       31     9      1       2019        2            11
CST          :biweekly  :tuesday     2019   1       31     9      1       2019        2            12
UTC          :biweekly  :wednesday   2019   1       31     9      1       2019        2            13
UTCplus      :biweekly  :thursday    2019   1       31     8      59      2019        2            7
UTCplus      :biweekly  :thursday    2019   1       31     9      1       2019        2            14
half-hour    :biweekly  :friday      2019   1       31     9      1       2019        2            8
EST          :biweekly  :saturday    2019   1       31     9      1       2019        2            9
CST          :biweekly  :sunday      2019   1       31     9      1       2019        2            10
;; Biweekly reminders spanning a year
EST          :biweekly  :monday      2018   12      31     8      59      2019        1            7
EST          :biweekly  :monday      2018   12      31     9      1       2019        1            14
CST          :biweekly  :tuesday     2018   12      31     9      1       2019        1            8
UTC          :biweekly  :wednesday   2018   12      31     9      1       2019        1            9
UTCplus      :biweekly  :thursday    2018   12      31     9      1       2019        1            10
half-hour    :biweekly  :friday      2018   12      31     9      1       2019        1            11
EST          :biweekly  :saturday    2018   12      31     9      1       2019        1            12
CST          :biweekly  :sunday      2018   12      31     9      1       2019        1            13)
 
  (tabular
    (facts "About monthly reminders"
  
      (:next-send (reminder-for ?cur-y ?cur-mo ?cur-d ?cur-h ?cur-mi ?frequency ?occurrence ?assignee-tz)) =>
        (verify ?reminder-y ?reminder-mo ?reminder-d ?assignee-tz))

?assignee-tz ?frequency ?occurrence  ?cur-y ?cur-mo ?cur-d ?cur-h ?cur-mi ?reminder-y ?reminder-mo ?reminder-d
;; Monthly reminders before 9AM
EST          :monthly  :first        2019   1       1      8      59      2019        1            1
CST          :monthly  :first-monday 2019   1       1      8      59      2019        1            7
UTC          :monthly  :last-friday  2019   1       1      8      59      2019        1            25
UTCplus      :monthly  :last         2019   1       1      8      59      2019        1            31
;; Monthly reminders at 9AM
half-hour    :monthly  :first        2019   1       1      9      0       2019        2            1
EST          :monthly  :first-monday 2019   1       1      9      0       2019        1            7
CST          :monthly  :last-friday  2019   1       1      9      0       2019        1            25
UTC          :monthly  :last         2019   1       1      9      0       2019        1            31
;; Monthly reminders after 9AM
UTCplus      :monthly  :first        2019   1       1      9      1       2019        2            1
half-hour    :monthly  :first-monday 2019   1       1      9      1       2019        1            7
EST          :monthly  :last-friday  2019   1       1      9      1       2019        1            25
CST          :monthly  :last         2019   1       1      9      1       2019        1            31
;; Monthly reminders spanning a month
UTC          :monthly  :first        2019   1       1      8      59      2019        1            1
UTCplus      :monthly  :first        2019   1       1      9      1       2019        2            1
half-hour    :monthly  :first-monday 2019   1       7      9      1       2019        2            4
EST          :monthly  :last-friday  2019   1       25     9      1       2019        2            22
CST          :monthly  :last         2019   1       31     8      59      2019        1            31
UTC          :monthly  :last         2019   1       31     9      1       2019        2            28
;; Monthly reminders spanning a year
UTCplus      :monthly  :first        2018   12      31     9      1       2019        1            1
half-hour    :monthly  :first-monday 2018   12      31     9      1       2019        1            7
EST          :monthly  :last-friday  2018   12      31     9      1       2019        1            25
CST          :monthly  :last         2018   12      31     8      59      2018        12           31
UTC          :monthly  :last         2018   12      31     9      1       2019        1            31)

  (tabular
    (facts "About quarterly reminders"
  
        (:next-send (reminder-for ?cur-y ?cur-mo ?cur-d ?cur-h ?cur-mi ?frequency ?occurrence ?assignee-tz)) =>
        (verify ?reminder-y ?reminder-mo ?reminder-d ?assignee-tz))

?assignee-tz ?frequency ?occurrence   ?cur-y ?cur-mo ?cur-d ?cur-h ?cur-mi ?reminder-y ?reminder-mo ?reminder-d
;; Quarterly reminders before 9AM
EST          :quarterly :first        2019   1       1      8      59      2019        1            1
CST          :quarterly :first-monday 2019   1       1      8      59      2019        1            7
UTC          :quarterly :last-friday  2019   1       1      8      59      2019        3            29
UTCplus      :quarterly :last         2019   1       1      8      59      2019        3            31
;; Quarterly reminders at 9AM
half-hour    :quarterly :first        2019   1       1      9      0       2019        4            1
EST          :quarterly :first-monday 2019   1       1      9      0       2019        1            7
CST          :quarterly :last-friday  2019   1       1      9      0       2019        3            29
UTC          :quarterly :last         2019   1       1      9      0       2019        3            31
;; Quarterly reminders after 9AM
UTCplus      :quarterly :first        2019   1       1      9      1       2019        4            1
half-hour    :quarterly :first-monday 2019   1       1      9      1       2019        1            7
EST          :quarterly :last-friday  2019   1       1      9      1       2019        3            29
CST          :quarterly :last         2019   1       1      9      1       2019        3            31
;; Quarterly reminders spanning a year
UTC          :quarterly :first        2018   10      1      7      59      2018        10           1
UTCplus      :quarterly :first        2018   10      1      9      1       2019        1            1
half-hour    :quarterly :first        2018   11      1      9      1       2019        1            1
EST          :quarterly :first        2018   12      1      9      1       2019        1            1
CST          :quarterly :first        2018   12      31     9      1       2019        1            1
UTC          :quarterly :first-monday 2018   10      1      7      59      2018        10           1
UTCplus      :quarterly :first-monday 2018   10      1      9      1       2019        1            7
half-hour    :quarterly :first-monday 2018   11      1      9      1       2019        1            7
EST          :quarterly :first-monday 2018   12      1      9      1       2019        1            7
CST          :quarterly :first-monday 2018   12      31     9      1       2019        1            7
UTC          :quarterly :last-friday  2018   11      1      9      1       2018        12           28
UTCplus      :quarterly :last-friday  2018   12      28     8      59      2018        12           28
half-hour    :quarterly :last-friday  2018   12      28     9      1       2019        3            29
EST          :quarterly :last         2018   11      1      9      1       2018        12           31
CST          :quarterly :last         2018   12      31     8      59      2018        12           31
UTC          :quarterly :last         2018   12      31     9      1       2019        3            31))

  (facts "About chains of subsequent reminders"

    (tabular
      (facts "About chains of reminders"
    
        (:next-send
          (reminder-chain-for ?chain ?cur-y ?cur-mo ?cur-d ?cur-h ?cur-mi ?frequency ?occurrence ?assignee-tz)) =>
          (verify ?reminder-y ?reminder-mo ?reminder-d ?assignee-tz))

?chain ?assignee-tz ?frequency ?occurrence   ?cur-y ?cur-mo ?cur-d ?cur-h ?cur-mi ?reminder-y ?reminder-mo ?reminder-d
;; Weekly reminders
2      EST          :weekly    :monday       2019   1       1      8      59      2019        1            14
3      CST          :weekly    :tuesday      2019   1       1      8      59      2019        1            15
4      UTC          :weekly    :tuesday      2019   1       1      9      0       2019        1            29
4      UTCplus      :weekly    :wednesday    2019   1       1      9      1       2019        1            23
5      half-hour    :weekly    :thursday     2019   1       1      9      1       2019        1            31
;; Biweekly reminders
2      EST          :biweekly  :monday       2019   1       1      8      59      2019        1            28
3      CST          :biweekly  :tuesday      2019   1       1      8      59      2019        2            5
4      UTC          :biweekly  :tuesday      2019   1       1      9      0       2019        2            26
4      UTCplus      :biweekly  :wednesday    2019   1       1      9      1       2019        2            20
5      half-hour    :biweekly  :thursday     2019   1       1      9      1       2019        3            7
;; Monthly reminders
2      EST          :monthly   :first        2019   1       1      8      59      2019        2            1
3      CST          :monthly   :first        2019   1       1      9      0       2019        4            1
4      UTC          :monthly   :first-monday 2019   1       1      9      1       2019        4            1
4      UTCplus      :monthly   :last-friday  2019   1       1      9      1       2019        4            26
5      half-hour    :monthly   :last         2019   1       1      9      1       2019        5            31
;; Quarterly reminders
2      EST          :quarterly :first        2019   1       1      8      59      2019        4            1
3      CST          :quarterly :first        2019   1       1      9      0       2019        10           1
4      UTC          :quarterly :first-monday 2019   1       1      9      1       2019        10           7
4      UTCplus      :quarterly :last-friday  2019   1       1      9      1       2019        12           27
5      half-hour    :quarterly :last         2019   1       1      9      1       2020        3            31)))