variable "project_id" {
  type    = string
  default = "capsnote"
}

variable "project_number" {
  type    = string
  default = "169516793433"
}

variable "zone" {
  type    = string
  default = "asia-northeast3-a"
  validation {
    condition     = contains(["asia-northeast3-a", "asia-northeast3-b", "asia-northeast3-c"], var.zone)
    error_message = "Use a Seoul zone after checking capacity and quota."
  }
}

variable "app_machine_type" {
  type    = string
  default = "e2-standard-2"
  validation {
    condition     = contains(["e2-standard-2", "e2-standard-4"], var.app_machine_type)
    error_message = "Use the reviewed 2/8 or 4/16 vCPU/GiB size."
  }
}

variable "app_image" {
  description = "Exact reviewed Ubuntu 24.04 amd64 image. Family aliases are forbidden."
  type        = string
  validation {
    condition     = can(regex("^projects/ubuntu-os-cloud/global/images/ubuntu-2404-noble-amd64-v[0-9]+$", var.app_image))
    error_message = "Resolve the exact dated Ubuntu 24.04 amd64 image after API activation; do not use a family."
  }
}

variable "db_tier" {
  type    = string
  default = "db-custom-2-7680"
  validation {
    condition     = contains(["db-custom-2-7680", "db-custom-4-15360"], var.db_tier)
    error_message = "Use the reviewed 2/7.5 or 4/15 vCPU/GiB SQL size."
  }
}

variable "db_availability_type" {
  description = "ZONAL preserves the agreed MVP; REGIONAL is an explicitly costed HA option."
  type        = string
  default     = "ZONAL"
  validation {
    condition     = contains(["ZONAL", "REGIONAL"], var.db_availability_type)
    error_message = "Expected ZONAL or REGIONAL."
  }
}

variable "db_disk_size_gb" {
  description = "Confirm source DB/index/WAL size before the migration; disks cannot shrink."
  type        = number
  default     = 50
  validation {
    condition     = var.db_disk_size_gb >= 50 && var.db_disk_size_gb <= 500 && floor(var.db_disk_size_gb) == var.db_disk_size_gb
    error_message = "SQL disk must be 50..500 GiB. Re-review the cost if changed."
  }
}

variable "github_federation_enabled" {
  description = "Keep false until new GCP workflows and GitHub environments have been reviewed."
  type        = bool
  default     = false
}

variable "notification_email" {
  description = "Optional during review; required and delivery-tested before serving production traffic."
  type        = string
  default     = null
  nullable    = true
}

locals {
  prefix = "capsnote-prod"
  region = "asia-northeast3"
  # GitHub REST metadata verified 2026-10-30. Names also bind reusable workflow refs.
  github_owner_id = "324880339"
  github_owner    = "CAUCapstoneDesignPipeline"
  github_repos = {
    be  = { id = "1397266620", name = "capstone_code_BE", registry = "capstone-be" }
    web = { id = "1397258129", name = "capstone_code_FE", registry = "capstone-web" }
  }
  # These paths are the planned GCP workflows, deliberately separate from existing AWS workflows.
  deploy_workflow = "${local.github_owner}/capstone_code_BE/.github/workflows/gcp-deploy-reusable.yml@${var.app_deploy_workflow_sha}"
  secret_ids      = toset(["db-password", "jwt-secret", "google-client-secret", "allowed-emails"])
}

variable "app_deploy_workflow_sha" {
  description = "Reviewed immutable BE reusable workflow commit, remotely reachable before federation activation."
  type        = string
  default     = "UNCONFIGURED"
}
variable "ai_deploy_workflow_sha" {
  description = "Reviewed immutable AI reusable workflow commit."
  type        = string
  default     = "UNCONFIGURED"
}
resource "terraform_data" "federation_gate" {
  lifecycle {
    precondition {
      condition     = !var.github_federation_enabled || (can(regex("^[a-f0-9]{40}$", var.app_deploy_workflow_sha)) && can(regex("^[a-f0-9]{40}$", var.ai_deploy_workflow_sha)))
      error_message = "Federation activation requires both reviewed immutable reusable workflow SHAs."
    }
  }
}
