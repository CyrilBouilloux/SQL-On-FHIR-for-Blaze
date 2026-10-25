{{- define "blaze.etiquettes" -}}
app.kubernetes.io/part-of: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{/* Contexte de sécurité du POD. runAsUser reste vide par défaut : le cluster
     impose le sien, rien ici n'en dépend. */}}
{{- define "blaze.sécuritéPod" -}}
runAsNonRoot: true
{{- if .Values.securite.runAsUser }}
runAsUser: {{ .Values.securite.runAsUser }}
{{- end }}
fsGroup: {{ .Values.securite.fsGroup }}
seccompProfile:
  type: RuntimeDefault
{{- end -}}

{{/* Les quatre exigences de la politique restreinte, écrites une fois. */}}
{{- define "blaze.sécuritéConteneur" -}}
allowPrivilegeEscalation: false
readOnlyRootFilesystem: true
capabilities:
  drop: ["ALL"]
{{- end -}}

{{/* Entier >= 1 attendu : renvoie sa forme décimale, ou échoue (jamais de repli).
     Appel : include "blaze.entier" (dict "nom" "blaze.x.y" "v" $valeur).
     Un entier lu depuis un fichier de valeurs est un float64 : 8589934592 ne doit
     pas se rendre en notation scientifique. */}}
{{- define "blaze.entier" -}}
{{- $v := .v -}}
{{- if kindIs "float64" $v }}{{ if eq $v (floor $v) }}{{ $v = printf "%d" (int64 $v) }}{{ end }}{{ end -}}
{{- if not (regexMatch "^[1-9][0-9]*$" (toString $v)) }}{{ fail (printf "%s : entier >= 1 attendu, reçu %v" .nom .v) }}{{ end -}}
{{- toString $v -}}
{{- end -}}
