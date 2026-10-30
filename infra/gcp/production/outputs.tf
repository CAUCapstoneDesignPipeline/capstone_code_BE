output "project_id" { value = var.project_id }
output "region" { value = local.region }
output "zone" { value = var.zone }
output "app_instance" { value = google_compute_instance.app.name }
output "app_external_ip" { value = google_compute_address.app.address }
output "sql_connection_name" { value = google_sql_database_instance.main.connection_name }
output "sql_private_ip" { value = google_sql_database_instance.main.private_ip_address }
output "artifact_repositories" {
  value = { for key, repo in local.github_repos : key => "${local.region}-docker.pkg.dev/${var.project_id}/${repo.registry}" }
}
output "workload_identity_provider" { value = google_iam_workload_identity_pool_provider.github.name }
output "publisher_service_accounts" { value = { for key, sa in google_service_account.publisher : key => sa.email } }
output "deployment_service_account" { value = google_service_account.deployer.email }
output "release_bucket" { value = google_storage_bucket.releases.name }
output "migration_bucket" { value = google_storage_bucket.migration.name }
output "runtime_secret_ids" { value = { for key, secret in google_secret_manager_secret.runtime : key => secret.secret_id } }
output "ai_enabled" { value = false }
