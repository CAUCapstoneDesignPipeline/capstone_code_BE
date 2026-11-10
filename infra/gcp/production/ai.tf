# AI infrastructure is created in the first provisioning batch. Product AI remains off
# until the BE integration and release contract are implemented and validated.
variable "ai_machine_type" {
  description = "50-day budget baseline: 2 vCPU / 16 GiB. Retain model memory; re-cost any override."
  type        = string
  default     = "e2-highmem-2"
  validation {
    condition     = contains(["e2-highmem-2", "e2-standard-4", "e2-standard-8"], var.ai_machine_type)
    error_message = "Keep at least 16 GiB for the CPU model; larger sizes require latency measurements and a revised budget."
  }
}

locals {
  ai_repo_id     = "1397258740" # GitHub REST verified 2026-10-30
  ai_repo        = "${local.github_owner}/capstone_code_AI"
  ai_release_ref = "refs/heads/develop" # Current AI default branch; protect before enabling WIF.
  ai_dns_name    = "${local.prefix}-ai.${var.zone}.c.${var.project_id}.internal"
  ai_secret_ids  = toset(["ai-service-token", "ai-provider-key", "ai-tls-bundle"])
  ai_ssh_members = {
    bootstrap = "serviceAccount:capstone-bootstrap@${var.project_id}.iam.gserviceaccount.com"
    deployer  = "serviceAccount:${google_service_account.ai_deployer.email}"
  }
}

resource "google_compute_subnetwork" "ai" {
  name                     = "${local.prefix}-ai"
  ip_cidr_range            = "10.60.2.0/24"
  region                   = local.region
  network                  = google_compute_network.app.id
  private_ip_google_access = true
}

resource "google_compute_address" "ai_private" {
  name         = "${local.prefix}-ai-private"
  address_type = "INTERNAL"
  address      = "10.60.2.10"
  region       = local.region
  subnetwork   = google_compute_subnetwork.ai.id
  lifecycle { prevent_destroy = true }
}

resource "google_compute_address" "ai_nat" {
  name         = "${local.prefix}-ai-nat"
  address_type = "EXTERNAL"
  network_tier = "PREMIUM"
  region       = local.region
  depends_on   = [google_project_service.required]
  lifecycle { prevent_destroy = true }
}

resource "google_compute_router" "ai" {
  name    = "${local.prefix}-ai"
  region  = local.region
  network = google_compute_network.app.id
}

resource "google_compute_router_nat" "ai" {
  name                               = "${local.prefix}-ai"
  router                             = google_compute_router.ai.name
  region                             = local.region
  nat_ip_allocate_option             = "MANUAL_ONLY"
  nat_ips                            = [google_compute_address.ai_nat.self_link]
  source_subnetwork_ip_ranges_to_nat = "LIST_OF_SUBNETWORKS"
  subnetwork {
    name                    = google_compute_subnetwork.ai.id
    source_ip_ranges_to_nat = ["PRIMARY_IP_RANGE"]
  }
  log_config {
    enable = true
    filter = "ERRORS_ONLY"
  }
}

resource "google_compute_firewall" "ai_from_app" {
  name                    = "${local.prefix}-ai-from-app"
  network                 = google_compute_network.app.name
  source_service_accounts = [google_service_account.runtime.email]
  target_service_accounts = [google_service_account.ai_runtime.email]
  allow {
    protocol = "tcp"
    ports    = ["8443"]
  }
}

resource "google_compute_firewall" "ai_iap" {
  name                    = "${local.prefix}-ai-iap"
  network                 = google_compute_network.app.name
  source_ranges           = ["35.235.240.0/20"]
  target_service_accounts = [google_service_account.ai_runtime.email]
  allow {
    protocol = "tcp"
    ports    = ["22"]
  }
}

resource "google_compute_firewall" "ai_no_database" {
  name                    = "${local.prefix}-ai-no-database"
  network                 = google_compute_network.app.name
  direction               = "EGRESS"
  priority                = 900
  destination_ranges      = ["${google_compute_global_address.sql_psa.address}/${google_compute_global_address.sql_psa.prefix_length}"]
  target_service_accounts = [google_service_account.ai_runtime.email]
  deny { protocol = "all" }
}

resource "google_compute_instance" "ai" {
  name                      = "${local.prefix}-ai"
  zone                      = var.zone
  machine_type              = var.ai_machine_type
  deletion_protection       = true
  allow_stopping_for_update = true
  boot_disk {
    initialize_params {
      image = var.app_image
      type  = "pd-balanced"
      size  = 100
    }
  }
  network_interface {
    subnetwork = google_compute_subnetwork.ai.id
    network_ip = google_compute_address.ai_private.address
    # No access_config: this VM has no external IP.
  }
  service_account {
    email  = google_service_account.ai_runtime.email
    scopes = ["https://www.googleapis.com/auth/cloud-platform"]
  }
  metadata = {
    enable-oslogin           = "TRUE"
    block-project-ssh-keys   = "TRUE"
    serial-port-enable       = "FALSE"
    disable-legacy-endpoints = "TRUE"
  }
  shielded_instance_config {
    enable_secure_boot          = true
    enable_vtpm                 = true
    enable_integrity_monitoring = true
  }
  scheduling {
    provisioning_model  = "STANDARD"
    automatic_restart   = true
    on_host_maintenance = "MIGRATE"
  }
  depends_on = [google_compute_router_nat.ai, google_project_iam_member.ai_runtime, google_compute_firewall.ai_no_database]
  # Base VM only; AI image, host TLS, model/IDF and metadata isolation are release gates.
  lifecycle { prevent_destroy = true }
}

resource "google_service_account" "ai_runtime" {
  account_id   = "${local.prefix}-ai-runtime"
  display_name = "CAPSNOTE private FastAPI VM"
  depends_on   = [google_project_service.required]
}
resource "google_service_account" "ai_publisher" {
  account_id   = "${local.prefix}-publish-ai"
  display_name = "CAPSNOTE AI image publisher"
  depends_on   = [google_project_service.required]
}
resource "google_service_account" "ai_deployer" {
  account_id   = "${local.prefix}-ai-deploy"
  display_name = "CAPSNOTE AI-only deployment"
  depends_on   = [google_project_service.required]
}
resource "google_project_iam_member" "ai_runtime" {
  for_each = toset(["roles/logging.logWriter", "roles/monitoring.metricWriter"])
  project  = var.project_id
  role     = each.value
  member   = "serviceAccount:${google_service_account.ai_runtime.email}"
}
resource "google_project_iam_member" "ai_deployment_lookup" {
  project = var.project_id
  role    = google_project_iam_custom_role.deployment_lookup.name
  member  = "serviceAccount:${google_service_account.ai_deployer.email}"
}
resource "google_compute_instance_iam_member" "ai_oslogin" {
  for_each      = local.ai_ssh_members
  zone          = var.zone
  instance_name = google_compute_instance.ai.name
  role          = "roles/compute.osAdminLogin"
  member        = each.value
}
resource "google_iap_tunnel_instance_iam_member" "ai_operator" {
  for_each = local.ai_ssh_members
  zone     = var.zone
  instance = google_compute_instance.ai.name
  role     = "roles/iap.tunnelResourceAccessor"
  member   = each.value
  condition {
    title      = "ssh_only"
    expression = "destination.port == 22"
  }
}
resource "google_service_account_iam_member" "ai_deployer_act_as" {
  service_account_id = google_service_account.ai_runtime.name
  role               = "roles/iam.serviceAccountUser"
  member             = "serviceAccount:${google_service_account.ai_deployer.email}"
}

resource "google_artifact_registry_repository" "ai" {
  location      = local.region
  repository_id = "capstone-ai"
  format        = "DOCKER"
  docker_config { immutable_tags = true }
  depends_on = [google_project_service.required]
  lifecycle { prevent_destroy = true }
}
resource "google_artifact_registry_repository_iam_member" "ai" {
  for_each = {
    runtime   = { role = "roles/artifactregistry.reader", email = google_service_account.ai_runtime.email }
    publisher = { role = "roles/artifactregistry.writer", email = google_service_account.ai_publisher.email }
    deployer  = { role = "roles/artifactregistry.reader", email = google_service_account.ai_deployer.email }
  }
  location   = local.region
  repository = google_artifact_registry_repository.ai.name
  role       = each.value.role
  member     = "serviceAccount:${each.value.email}"
}

resource "google_storage_bucket" "ai" {
  for_each                    = toset(["artifacts", "releases"])
  name                        = "${var.project_id}-${var.project_number}-ai-${each.key}"
  location                    = "ASIA-NORTHEAST3"
  storage_class               = "STANDARD"
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
  force_destroy               = false
  deletion_policy             = "PREVENT"
  versioning { enabled = true }
  soft_delete_policy { retention_duration_seconds = 604800 }
  lifecycle { prevent_destroy = true }
}
resource "google_storage_bucket_iam_member" "ai" {
  for_each = {
    runtime_artifacts  = { bucket = "artifacts", role = "roles/storage.objectViewer", email = google_service_account.ai_runtime.email }
    deployer_artifacts = { bucket = "artifacts", role = "roles/storage.objectViewer", email = google_service_account.ai_deployer.email }
    runtime_releases   = { bucket = "releases", role = "roles/storage.objectUser", email = google_service_account.ai_runtime.email }
    deployer_releases  = { bucket = "releases", role = "roles/storage.objectUser", email = google_service_account.ai_deployer.email }
  }
  bucket = google_storage_bucket.ai[each.value.bucket].name
  role   = each.value.role
  member = "serviceAccount:${each.value.email}"
}

resource "google_secret_manager_secret" "ai" {
  for_each  = local.ai_secret_ids
  secret_id = "${local.prefix}-${each.key}"
  replication {
    user_managed {
      replicas { location = local.region }
    }
  }
  depends_on = [google_project_service.required]
  lifecycle { prevent_destroy = true }
}
resource "google_secret_manager_secret_iam_member" "ai" {
  for_each  = local.ai_secret_ids
  secret_id = google_secret_manager_secret.ai[each.key].id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.ai_runtime.email}"
}
resource "google_secret_manager_secret_iam_member" "app_ai_token" {
  secret_id = google_secret_manager_secret.ai["ai-service-token"].id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.runtime.email}"
}

resource "google_service_account_iam_member" "ai_publisher_federation" {
  service_account_id = google_service_account.ai_publisher.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/${google_iam_workload_identity_pool.github.name}/attribute.job_scope/${local.ai_repo_id}_image-publish"
}
resource "google_service_account_iam_member" "ai_deployer_federation" {
  service_account_id = google_service_account.ai_deployer.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/${google_iam_workload_identity_pool.github.name}/attribute.job_scope/${local.ai_repo_id}_ai-production"
}

output "ai_infrastructure_planned" { value = true }
output "ai_instance" { value = google_compute_instance.ai.name }
output "ai_internal_url" { value = "https://${local.ai_dns_name}:8443" }
output "ai_private_ip" { value = google_compute_address.ai_private.address }
output "ai_nat_external_ip" { value = google_compute_address.ai_nat.address }
output "ai_artifact_repository" { value = "${local.region}-docker.pkg.dev/${var.project_id}/capstone-ai" }
output "ai_buckets" { value = { for key, bucket in google_storage_bucket.ai : key => bucket.name } }
output "ai_secrets" { value = { for key, secret in google_secret_manager_secret.ai : key => secret.secret_id } }
output "ai_publisher_service_account" { value = google_service_account.ai_publisher.email }
output "ai_deployment_service_account" { value = google_service_account.ai_deployer.email }
