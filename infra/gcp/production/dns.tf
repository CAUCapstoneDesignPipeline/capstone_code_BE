variable "prepare_public_dns" {
  description = "Provision the public zone for inventory and comparison before delegation."
  type        = bool
  default     = false
}

resource "google_project_service" "dns" {
  count              = var.prepare_public_dns ? 1 : 0
  service            = "dns.googleapis.com"
  disable_on_destroy = false
}

resource "google_dns_managed_zone" "public" {
  count       = var.prepare_public_dns ? 1 : 0
  name        = "capsnote-art-prod"
  dns_name    = "capsnote.art."
  description = "CAPSNOTE public authoritative zone"
  visibility  = "public"
  depends_on  = [google_project_service.dns]

  lifecycle {
    prevent_destroy = true
  }
}
