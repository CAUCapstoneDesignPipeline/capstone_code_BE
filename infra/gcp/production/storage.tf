resource "google_artifact_registry_repository" "images" {
  for_each      = local.github_repos
  location      = local.region
  repository_id = each.value.registry
  format        = "DOCKER"
  docker_config { immutable_tags = true }
  # No automatic image deletion until current/previous release digest retention is implemented.
  depends_on = [google_project_service.required]
  lifecycle { prevent_destroy = true }
}

resource "google_storage_bucket" "releases" {
  name                        = "${var.project_id}-${var.project_number}-releases"
  location                    = "ASIA-NORTHEAST3"
  storage_class               = "STANDARD"
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
  force_destroy               = false
  deletion_policy             = "PREVENT"
  versioning { enabled = true }
  soft_delete_policy { retention_duration_seconds = 604800 }
  # Do not expire current or previous manifests. Lifecycle can be added after release retention exists.
  lifecycle { prevent_destroy = true }
}

resource "google_storage_bucket" "migration" {
  name                        = "${var.project_id}-${var.project_number}-migration"
  location                    = "ASIA-NORTHEAST3"
  storage_class               = "STANDARD"
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
  force_destroy               = false
  deletion_policy             = "PREVENT"
  soft_delete_policy { retention_duration_seconds = 604800 }
  # Deliberately no expiry before migration acceptance; explicitly remove dumps after sign-off.
  lifecycle { prevent_destroy = true }
}

resource "google_secret_manager_secret" "runtime" {
  for_each  = local.secret_ids
  secret_id = "${local.prefix}-${each.key}"
  replication {
    user_managed {
      replicas { location = local.region }
    }
  }
  depends_on = [google_project_service.required]
  lifecycle { prevent_destroy = true }
}
