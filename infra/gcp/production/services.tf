locals {
  services = toset([
    "serviceusage.googleapis.com", "cloudresourcemanager.googleapis.com",
    "compute.googleapis.com", "servicenetworking.googleapis.com", "sqladmin.googleapis.com",
    "artifactregistry.googleapis.com", "secretmanager.googleapis.com", "storage.googleapis.com",
    "iam.googleapis.com", "iamcredentials.googleapis.com", "sts.googleapis.com",
    "iap.googleapis.com", "oslogin.googleapis.com", "logging.googleapis.com", "monitoring.googleapis.com"
  ])
}

resource "google_project_service" "required" {
  for_each           = local.services
  service            = each.value
  disable_on_destroy = false
}
