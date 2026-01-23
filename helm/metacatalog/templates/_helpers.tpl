{{/*
Expand the name of the chart.
*/}}
{{- define "metacatalog.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
We truncate at 63 chars because some Kubernetes name fields are limited to this (by the DNS naming spec).
If release name contains chart name it will be used as a full name.
*/}}
{{- define "metacatalog.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "metacatalog.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "metacatalog.labels" -}}
helm.sh/chart: {{ include "metacatalog.chart" . }}
{{ include "metacatalog.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "metacatalog.selectorLabels" -}}
app.kubernetes.io/name: {{ include "metacatalog.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Create the name of the service account to use
*/}}
{{- define "metacatalog.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "metacatalog.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Create the database host
*/}}
{{- define "metacatalog.databaseHost" -}}
{{- if .Values.database.external }}
{{- .Values.database.host }}
{{- else }}
{{- printf "%s-postgresql" .Release.Name }}
{{- end }}
{{- end }}

{{/*
Create the database port
*/}}
{{- define "metacatalog.databasePort" -}}
{{- .Values.database.port | default 5432 }}
{{- end }}

{{/*
Create the database name
*/}}
{{- define "metacatalog.databaseName" -}}
{{- if .Values.database.external }}
{{- .Values.database.name }}
{{- else }}
{{- .Values.postgresql.auth.database | default "metacatalog" }}
{{- end }}
{{- end }}

{{/*
Create the secret name for database credentials
*/}}
{{- define "metacatalog.databaseSecretName" -}}
{{- if .Values.database.existingSecret }}
{{- .Values.database.existingSecret }}
{{- else }}
{{- include "metacatalog.fullname" . }}-db
{{- end }}
{{- end }}

{{/*
Create the database username key in secret
*/}}
{{- define "metacatalog.databaseUsernameKey" -}}
{{- if .Values.database.existingSecret -}}
{{- .Values.database.existingSecretUsernameKey -}}
{{- else -}}
username
{{- end -}}
{{- end }}

{{/*
Create the database password key in secret
*/}}
{{- define "metacatalog.databasePasswordKey" -}}
{{- if .Values.database.existingSecret -}}
{{- .Values.database.existingSecretPasswordKey -}}
{{- else -}}
password
{{- end -}}
{{- end }}
