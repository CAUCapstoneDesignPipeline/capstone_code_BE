# Provider mocks do not call Google or create resources. These tests verify plan-time invariants.
mock_provider "google" {}
variables {
  app_image               = "projects/ubuntu-os-cloud/global/images/ubuntu-2404-noble-amd64-v20261001"
  app_deploy_workflow_sha = "1111111111111111111111111111111111111111"
  ai_deploy_workflow_sha  = "2222222222222222222222222222222222222222"
}

run "mvp_security_boundaries" {
  command = plan
  assert {
    condition = (
      google_sql_database_instance.main.settings[0].ip_configuration[0].ipv4_enabled == false &&
      google_sql_database_instance.main.settings[0].connector_enforcement == "REQUIRED" &&
      google_sql_database_instance.main.settings[0].ip_configuration[0].ssl_mode == "ENCRYPTED_ONLY"
    )
    error_message = "SQL must require connectors, TLS, and a private address."
  }
  assert {
    condition = (
      google_sql_database_instance.main.deletion_protection &&
      google_sql_database_instance.main.settings[0].deletion_protection_enabled &&
      google_sql_database_instance.main.settings[0].backup_configuration[0].point_in_time_recovery_enabled &&
      google_sql_database_instance.main.settings[0].backup_configuration[0].transaction_log_retention_days == 7 &&
      google_compute_instance.app.deletion_protection
    )
    error_message = "Data/VM deletion guards and seven-day PITR must remain enabled."
  }
  assert {
    condition = (
      toset(google_compute_firewall.iap_ssh.source_ranges) == toset(["35.235.240.0/20"]) &&
      one(google_compute_firewall.iap_ssh.allow).ports == tolist(["22"]) &&
      toset(one(google_compute_firewall.https.allow).ports) == toset(["80", "443"]) &&
      google_compute_instance.app.metadata["enable-oslogin"] == "TRUE"
    )
    error_message = "Only HTTP(S) may be public; SSH must use IAP and OS Login."
  }
  assert {
    condition = (
      google_iam_workload_identity_pool.github.disabled &&
      google_iam_workload_identity_pool_provider.github.disabled &&
      output.ai_enabled == false &&
      alltrue([for policy in google_monitoring_alert_policy.capacity : !policy.enabled]) &&
      alltrue([for policy in google_monitoring_alert_policy.runtime_health : !policy.enabled])
    )
    error_message = "Federation, AI and recipient-less alerts must stay inactive during initial review."
  }
  assert {
    condition = (
      google_storage_bucket.releases.public_access_prevention == "enforced" &&
      google_storage_bucket.migration.public_access_prevention == "enforced" &&
      google_storage_bucket.releases.versioning[0].enabled &&
      alltrue([for repo in google_artifact_registry_repository.images : repo.docker_config[0].immutable_tags])
    )
    error_message = "Release history, private buckets and immutable image tags are required."
  }
  assert {
    condition = alltrue([
      strcontains(google_iam_workload_identity_pool_provider.github.attribute_condition, "assertion.repository_owner_id == '324880339'"),
      strcontains(google_iam_workload_identity_pool_provider.github.attribute_condition, "assertion.ref == 'refs/heads/main'"),
      strcontains(google_iam_workload_identity_pool_provider.github.attribute_condition, "gcp-deploy-reusable.yml@1111111111111111111111111111111111111111"),
      strcontains(google_iam_workload_identity_pool_provider.github.attribute_mapping["attribute.job_scope"], "assertion.repository_id"),
      length(google_service_account_iam_member.publisher_federation) == 2,
      length(google_service_account_iam_member.deployer_federation) == 2
    ])
    error_message = "WIF must constrain owner, caller repository, branch, environment and reusable workflow."
  }
}

run "optional_ha_and_notifications" {
  command = plan
  variables {
    db_availability_type = "REGIONAL"
    notification_email   = "test@example.com"
  }
  assert {
    condition = (
      google_sql_database_instance.main.settings[0].availability_type == "REGIONAL" &&
      google_sql_database_instance.main.settings[0].location_preference[0].secondary_zone != var.zone &&
      alltrue([for policy in google_monitoring_alert_policy.capacity : policy.enabled]) &&
      alltrue([for policy in google_monitoring_alert_policy.runtime_health : policy.enabled]) &&
      length(google_monitoring_notification_channel.email) == 1
    )
    error_message = "HA needs a different standby zone and enabled alerts need a recipient."
  }
}

run "reject_shared_cpu_size" {
  command = plan
  variables { app_machine_type = "e2-medium" }
  expect_failures = [var.app_machine_type]
}

run "ai_private_network_and_identity" {
  command = plan
  assert {
    condition = (
      length(google_compute_instance.ai.network_interface[0].access_config) == 0 &&
      google_compute_subnetwork.ai.private_ip_google_access &&
      google_compute_router_nat.ai.source_subnetwork_ip_ranges_to_nat == "LIST_OF_SUBNETWORKS" &&
      length(google_compute_router_nat.ai.subnetwork) == 1 &&
      google_compute_router_nat.ai.nat_ip_allocate_option == "MANUAL_ONLY"
    )
    error_message = "AI must have no public IP; outbound NAT must cover only its subnet."
  }
  assert {
    condition = (
      toset(one(google_compute_firewall.ai_from_app.allow).ports) == toset(["8443"]) &&
      length(google_compute_firewall.ai_from_app.source_service_accounts) == 1 &&
      length(coalesce(google_compute_firewall.ai_from_app.source_ranges, toset([]))) == 0 &&
      toset(google_compute_firewall.ai_iap.source_ranges) == toset(["35.235.240.0/20"]) &&
      google_compute_firewall.ai_no_database.direction == "EGRESS" &&
      google_compute_firewall.ai_no_database.priority == 900 &&
      toset(google_compute_firewall.ai_no_database.destination_ranges) == toset(["10.61.0.0/16"]) &&
      one(google_compute_firewall.ai_no_database.deny).protocol == "all"
    )
    error_message = "Only app TLS and IAP SSH may reach AI, and AI must be denied SQL network access."
  }
  assert {
    condition = (
      toset(keys(google_project_iam_member.ai_runtime)) == toset(["roles/logging.logWriter", "roles/monitoring.metricWriter"]) &&
      !contains(keys(google_secret_manager_secret_iam_member.ai), "db-password") &&
      strcontains(google_iam_workload_identity_pool_provider.github.attribute_condition, "assertion.repository_id == '1397258740'") &&
      strcontains(google_iam_workload_identity_pool_provider.github.attribute_condition, "refs/heads/develop") &&
      strcontains(google_iam_workload_identity_pool_provider.github.attribute_condition, "ai-production") &&
      output.ai_infrastructure_planned && !output.ai_enabled
    )
    error_message = "AI needs separate credentials and deployment identity, without enabling BE analysis."
  }
}

run "reject_image_family" {
  command = plan
  variables { app_image = "projects/ubuntu-os-cloud/global/images/family/ubuntu-2404-lts-amd64" }
  expect_failures = [var.app_image]
}
run "reject_unreviewed_federation" {
  command = plan
  variables {
    github_federation_enabled = true
    app_deploy_workflow_sha   = "UNCONFIGURED"
  }
  expect_failures = [terraform_data.federation_gate]
}

run "budget_is_project_scoped_and_not_a_shutdown" {
  command = plan
  variables {
    notification_email = "test@example.com"
    budget = {
      billing_account = "AAAAAA-BBBBBB-CCCCCC"
      currency_code   = "USD"
      monthly_amount  = 500
    }
  }
  assert {
    condition = (
      toset(google_billing_budget.project[0].budget_filter[0].projects) == toset(["projects/169516793433"]) &&
      google_billing_budget.project[0].budget_filter[0].credit_types_treatment == "EXCLUDE_ALL_CREDITS" &&
      google_billing_budget.project[0].amount[0].specified_amount[0].currency_code == "USD" &&
      google_billing_budget.project[0].amount[0].specified_amount[0].units == "500" &&
      google_billing_budget.project[0].all_updates_rule[0].disable_default_iam_recipients &&
      !google_billing_budget.project[0].all_updates_rule[0].enable_project_level_recipients &&
      length(google_billing_budget.project[0].threshold_rules) == 4 &&
      google_billing_budget.project[0].deletion_policy == "PREVENT"
    )
    error_message = "Budget must be project-only, in verified currency, with explicit recipients and current/forecast alerts."
  }
}

run "budget_requires_recipient" {
  command = plan
  variables {
    budget = {
      billing_account = "AAAAAA-BBBBBB-CCCCCC"
      currency_code   = "USD"
      monthly_amount  = 500
    }
  }
  expect_failures = [google_billing_budget.project[0]]
}
