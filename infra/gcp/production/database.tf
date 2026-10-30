resource "google_sql_database_instance" "main" {
  name                = "${local.prefix}-pg16"
  region              = local.region
  database_version    = "POSTGRES_16"
  deletion_protection = true
  settings {
    edition                     = "ENTERPRISE"
    tier                        = var.db_tier
    availability_type           = var.db_availability_type
    activation_policy           = "ALWAYS"
    disk_type                   = "PD_SSD"
    disk_size                   = var.db_disk_size_gb
    disk_autoresize             = true
    disk_autoresize_limit       = 500
    deletion_protection_enabled = true
    connector_enforcement       = "REQUIRED"
    ip_configuration {
      ipv4_enabled                                  = false
      private_network                               = google_compute_network.app.id
      allocated_ip_range                            = google_compute_global_address.sql_psa.name
      enable_private_path_for_google_cloud_services = false
      ssl_mode                                      = "ENCRYPTED_ONLY"
    }
    location_preference {
      zone           = var.zone
      secondary_zone = var.db_availability_type == "REGIONAL" ? (var.zone == "asia-northeast3-b" ? "asia-northeast3-c" : "asia-northeast3-b") : null
    }
    backup_configuration {
      enabled                        = true
      start_time                     = "18:00" # 03:00 KST
      location                       = local.region
      point_in_time_recovery_enabled = true
      transaction_log_retention_days = 7
      backup_retention_settings {
        retained_backups = 7
        retention_unit   = "COUNT"
      }
    }
    maintenance_window {
      day          = 7
      hour         = 19 # Monday 04:00 KST
      update_track = "stable"
    }
  }
  depends_on = [google_service_networking_connection.sql]
  lifecycle { prevent_destroy = true }
}

# Empty database only. Restore the source (including Flyway history) BEFORE starting BE.
# Do not put SQL passwords or google_sql_user resources in state.
resource "google_sql_database" "app" {
  name     = "capstone"
  instance = google_sql_database_instance.main.name
  lifecycle { prevent_destroy = true }
}
