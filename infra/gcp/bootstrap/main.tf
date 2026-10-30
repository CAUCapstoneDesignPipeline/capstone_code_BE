# First apply uses LOCAL state; migrate this state to this bucket afterwards.
# Do not manage the existing project, billing link, or bootstrap service account.
variable "project_id" {
  type    = string
  default = "capsnote"
}
variable "project_number" {
  type    = string
  default = "169516793433"
}
resource "google_storage_bucket" "state" {
  name                        = "${var.project_id}-${var.project_number}-tfstate"
  location                    = "ASIA-NORTHEAST3"
  storage_class               = "STANDARD"
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
  force_destroy               = false
  deletion_policy             = "PREVENT"
  versioning { enabled = true }
  soft_delete_policy { retention_duration_seconds = 604800 }
  lifecycle_rule {
    action { type = "Delete" }
    condition {
      with_state                 = "ARCHIVED"
      days_since_noncurrent_time = 90
      num_newer_versions         = 20
    }
  }
  lifecycle { prevent_destroy = true }
}
output "state_bucket" { value = google_storage_bucket.state.name }
