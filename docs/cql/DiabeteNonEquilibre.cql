library DiabeteNonEquilibre version '1.0.0'

using FHIR version '4.0.1'
include FHIRHelpers version '4.0.1'

codesystem "LOINC": 'http://loinc.org'
codesystem "SNOMED": 'http://snomed.info/sct'
code "HbA1c": '4548-4' from "LOINC"
code "Diabete type 2": '44054006' from "SNOMED"

context Patient

define "Age": AgeInYears()
define "Diabetique": exists ([Condition: "Diabete type 2"])
define "Derniere HbA1c":
  Last([Observation: "HbA1c"] O where O.status = 'final' sort by effective)
define "Valeur HbA1c": "Derniere HbA1c".value as Quantity
define "Non equilibre": "Diabetique" and "Valeur HbA1c" > 9 '%'
