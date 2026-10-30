terraform {
  required_version = ">= 1.11.0, < 2.0.0"
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "8.9.0"
    }
  }
}

# Use the existing impersonated ADC. Do not impersonate the same SA a second time.
provider "google" {
  project = var.project_id
  region  = "asia-northeast3"
  default_labels = {
    application = "capsnote"
    environment = "production"
    managed_by  = "terraform"
  }
}
