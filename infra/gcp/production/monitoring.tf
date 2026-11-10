resource "google_monitoring_notification_channel" "email" {
  count        = var.notification_email == null ? 0 : 1
  display_name = "CAPSNOTE operations"
  type         = "email"
  labels       = { email_address = var.notification_email }
  depends_on   = [google_project_service.required]
}

locals {
  alerts = {
    ai_cpu = {
      filter    = "resource.type = \"gce_instance\" AND resource.labels.instance_id = \"${google_compute_instance.ai.instance_id}\" AND metric.type = \"compute.googleapis.com/instance/cpu/utilization\""
      threshold = 0.7
    }
    ai_memory = {
      filter    = "resource.type = \"gce_instance\" AND resource.labels.instance_id = \"${google_compute_instance.ai.instance_id}\" AND metric.type = \"agent.googleapis.com/memory/percent_used\" AND metric.labels.state = \"used\""
      threshold = 80
    }
    ai_disk = {
      filter    = "resource.type = \"gce_instance\" AND resource.labels.instance_id = \"${google_compute_instance.ai.instance_id}\" AND metric.type = \"agent.googleapis.com/disk/percent_used\" AND metric.labels.state = \"used\""
      threshold = 80
    }
    app_cpu = {
      filter    = "resource.type = \"gce_instance\" AND resource.labels.instance_id = \"${google_compute_instance.app.instance_id}\" AND metric.type = \"compute.googleapis.com/instance/cpu/utilization\""
      threshold = 0.7
    }
    app_memory = {
      filter    = "resource.type = \"gce_instance\" AND resource.labels.instance_id = \"${google_compute_instance.app.instance_id}\" AND metric.type = \"agent.googleapis.com/memory/percent_used\" AND metric.labels.state = \"used\""
      threshold = 80
    }
    app_disk = {
      filter    = "resource.type = \"gce_instance\" AND resource.labels.instance_id = \"${google_compute_instance.app.instance_id}\" AND metric.type = \"agent.googleapis.com/disk/percent_used\" AND metric.labels.state = \"used\""
      threshold = 80
    }
    sql_cpu = {
      filter    = "resource.type = \"cloudsql_database\" AND resource.labels.database_id = \"${var.project_id}:${google_sql_database_instance.main.name}\" AND metric.type = \"cloudsql.googleapis.com/database/cpu/utilization\""
      threshold = 0.7
    }
    sql_disk = {
      filter    = "resource.type = \"cloudsql_database\" AND resource.labels.database_id = \"${var.project_id}:${google_sql_database_instance.main.name}\" AND metric.type = \"cloudsql.googleapis.com/database/disk/utilization\""
      threshold = 0.8
    }
  }
}

resource "google_monitoring_alert_policy" "capacity" {
  for_each              = local.alerts
  display_name          = "${local.prefix}-${each.key}"
  combiner              = "OR"
  enabled               = var.notification_email != null
  notification_channels = google_monitoring_notification_channel.email[*].name
  conditions {
    display_name = "Sustained usage for 10 minutes"
    condition_threshold {
      filter          = each.value.filter
      comparison      = "COMPARISON_GT"
      threshold_value = each.value.threshold
      duration        = "600s"
      aggregations {
        alignment_period   = "60s"
        per_series_aligner = "ALIGN_MEAN"
      }
      trigger { count = 1 }
    }
  }
  documentation {
    content   = "Check workload, process memory, disk usage and release state before resizing. Agent metrics require the GCP runtime installation."
    mime_type = "text/markdown"
  }
  depends_on = [google_project_service.required]
}

resource "google_monitoring_alert_policy" "runtime_health" {
  for_each              = toset(["app", "ai"])
  display_name          = "${local.prefix}-${each.key}-readiness-tls"
  combiner              = "OR"
  enabled               = var.notification_email != null
  notification_channels = google_monitoring_notification_channel.email[*].name
  conditions {
    display_name = "Runtime degraded or TLS certificate expires within 30 days"
    condition_matched_log {
      filter = "jsonPayload.component=\"capstone-${each.key}\" AND (jsonPayload.state=\"degraded\" OR jsonPayload.tlsExpiring=true)"
    }
  }
  alert_strategy {
    auto_close = "1800s"
    notification_rate_limit { period = "300s" }
  }
  depends_on = [google_project_service.required]
}
