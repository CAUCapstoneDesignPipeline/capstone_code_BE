resource "google_service_account" "runtime" {
  account_id   = "${local.prefix}-runtime"
  display_name = "CAPSNOTE application VM"
  depends_on   = [google_project_service.required]
}

resource "google_service_account" "publisher" {
  for_each     = local.github_repos
  account_id   = "${local.prefix}-publish-${each.key}"
  display_name = "CAPSNOTE ${each.key} image publisher"
  depends_on   = [google_project_service.required]
}

resource "google_service_account" "deployer" {
  account_id   = "${local.prefix}-deploy"
  display_name = "CAPSNOTE production deployment"
  depends_on   = [google_project_service.required]
}

resource "google_project_iam_member" "runtime" {
  for_each = toset(["roles/cloudsql.client", "roles/logging.logWriter", "roles/monitoring.metricWriter"])
  project  = var.project_id
  role     = each.value
  member   = "serviceAccount:${google_service_account.runtime.email}"
}

resource "google_artifact_registry_repository_iam_member" "publish" {
  for_each   = local.github_repos
  location   = local.region
  repository = google_artifact_registry_repository.images[each.key].name
  role       = "roles/artifactregistry.writer"
  member     = "serviceAccount:${google_service_account.publisher[each.key].email}"
}

resource "google_artifact_registry_repository_iam_member" "runtime" {
  for_each   = local.github_repos
  location   = local.region
  repository = google_artifact_registry_repository.images[each.key].name
  role       = "roles/artifactregistry.reader"
  member     = "serviceAccount:${google_service_account.runtime.email}"
}

resource "google_artifact_registry_repository_iam_member" "deployer" {
  for_each   = local.github_repos
  location   = local.region
  repository = google_artifact_registry_repository.images[each.key].name
  role       = "roles/artifactregistry.reader"
  member     = "serviceAccount:${google_service_account.deployer.email}"
}

resource "google_secret_manager_secret_iam_member" "runtime" {
  for_each  = local.secret_ids
  secret_id = google_secret_manager_secret.runtime[each.key].id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.runtime.email}"
}

resource "google_storage_bucket_iam_member" "runtime_releases" {
  bucket = google_storage_bucket.releases.name
  role   = "roles/storage.objectUser"
  member = "serviceAccount:${google_service_account.runtime.email}"
}

resource "google_storage_bucket_iam_member" "deployer_releases" {
  bucket = google_storage_bucket.releases.name
  role   = "roles/storage.objectUser"
  member = "serviceAccount:${google_service_account.deployer.email}"
}

resource "google_project_iam_custom_role" "deployment_lookup" {
  role_id     = "capsnoteDeploymentLookup"
  title       = "CAPSNOTE deployment instance lookup"
  description = "Read lookup used by gcloud compute ssh with an explicit instance, zone and project."
  permissions = ["compute.instances.get", "compute.projects.get"]
  depends_on  = [google_project_service.required]
}

resource "google_project_iam_member" "deployment_lookup" {
  project = var.project_id
  role    = google_project_iam_custom_role.deployment_lookup.name
  member  = "serviceAccount:${google_service_account.deployer.email}"
}

locals {
  ssh_principals = {
    bootstrap = "serviceAccount:capstone-bootstrap@${var.project_id}.iam.gserviceaccount.com"
    deployer  = "serviceAccount:${google_service_account.deployer.email}"
  }
}

resource "google_compute_instance_iam_member" "operator_oslogin" {
  for_each      = local.ssh_principals
  zone          = var.zone
  instance_name = google_compute_instance.app.name
  role          = "roles/compute.osAdminLogin"
  member        = each.value
}

resource "google_iap_tunnel_instance_iam_member" "operator" {
  for_each = local.ssh_principals
  zone     = var.zone
  instance = google_compute_instance.app.name
  role     = "roles/iap.tunnelResourceAccessor"
  member   = each.value
  condition {
    title      = "ssh_only"
    expression = "destination.port == 22"
  }
}

resource "google_service_account_iam_member" "deployer_act_as_runtime" {
  service_account_id = google_service_account.runtime.name
  role               = "roles/iam.serviceAccountUser"
  member             = "serviceAccount:${google_service_account.deployer.email}"
}

resource "google_iam_workload_identity_pool" "github" {
  workload_identity_pool_id = "capsnote-github"
  display_name              = "CAPSNOTE GitHub Actions"
  disabled                  = !var.github_federation_enabled
  depends_on                = [google_project_service.required, terraform_data.federation_gate]
}

resource "google_iam_workload_identity_pool_provider" "github" {
  workload_identity_pool_id          = google_iam_workload_identity_pool.github.workload_identity_pool_id
  workload_identity_pool_provider_id = "github"
  disabled                           = !var.github_federation_enabled
  attribute_mapping = {
    "google.subject"                = "assertion.sub"
    "attribute.repository_id"       = "assertion.repository_id"
    "attribute.repository_owner_id" = "assertion.repository_owner_id"
    "attribute.ref"                 = "assertion.ref"
    "attribute.job_scope"           = "assertion.repository_id + '_' + ('environment' in assertion ? assertion.environment : '')"
  }
  attribute_condition = <<-CEL
    assertion.repository_owner_id == '${local.github_owner_id}' &&
    'environment' in assertion &&
    (
      (assertion.repository_id in ['${local.github_repos.be.id}', '${local.github_repos.web.id}'] &&
       assertion.ref == 'refs/heads/main' &&
       (
         (assertion.environment == 'image-publish' &&
           ((assertion.repository_id == '${local.github_repos.be.id}' && assertion.workflow_ref == '${local.github_owner}/${local.github_repos.be.name}/.github/workflows/gcp-publish-image.yml@refs/heads/main') ||
            (assertion.repository_id == '${local.github_repos.web.id}' && assertion.workflow_ref == '${local.github_owner}/${local.github_repos.web.name}/.github/workflows/gcp-publish-image.yml@refs/heads/main'))) ||
         (assertion.environment == 'production' && 'job_workflow_ref' in assertion &&
           assertion.job_workflow_ref == '${local.deploy_workflow}' &&
           ((assertion.repository_id == '${local.github_repos.be.id}' && assertion.workflow_ref == '${local.github_owner}/${local.github_repos.be.name}/.github/workflows/gcp-deploy.yml@refs/heads/main') ||
            (assertion.repository_id == '${local.github_repos.web.id}' && assertion.workflow_ref == '${local.github_owner}/${local.github_repos.web.name}/.github/workflows/gcp-deploy.yml@refs/heads/main')))
       )) ||
      (assertion.repository_id == '${local.ai_repo_id}' && assertion.ref == '${local.ai_release_ref}' &&
       (
         (assertion.environment == 'image-publish' &&
           assertion.workflow_ref == '${local.ai_repo}/.github/workflows/gcp-publish-image.yml@${local.ai_release_ref}') ||
         (assertion.environment == 'ai-production' && 'job_workflow_ref' in assertion &&
           assertion.job_workflow_ref == '${local.ai_repo}/.github/workflows/gcp-deploy-reusable.yml@${var.ai_deploy_workflow_sha}' &&
           assertion.workflow_ref == '${local.ai_repo}/.github/workflows/gcp-deploy.yml@${local.ai_release_ref}')
       ))
    )
  CEL
  oidc { issuer_uri = "https://token.actions.githubusercontent.com" }
  # Empty allowed_audiences uses Google's provider-resource-name audience validation.
}

resource "google_service_account_iam_member" "publisher_federation" {
  for_each           = local.github_repos
  service_account_id = google_service_account.publisher[each.key].name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/${google_iam_workload_identity_pool.github.name}/attribute.job_scope/${each.value.id}_image-publish"
}

resource "google_service_account_iam_member" "deployer_federation" {
  for_each           = local.github_repos
  service_account_id = google_service_account.deployer.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/${google_iam_workload_identity_pool.github.name}/attribute.job_scope/${each.value.id}_production"
}
