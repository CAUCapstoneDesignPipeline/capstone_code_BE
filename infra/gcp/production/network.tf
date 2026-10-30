resource "google_compute_network" "app" {
  name                    = local.prefix
  auto_create_subnetworks = false
  routing_mode            = "REGIONAL"
  depends_on              = [google_project_service.required]
}

resource "google_compute_subnetwork" "app" {
  name                     = "${local.prefix}-app"
  ip_cidr_range            = "10.60.1.0/24"
  region                   = local.region
  network                  = google_compute_network.app.id
  private_ip_google_access = true
}

resource "google_compute_global_address" "sql_psa" {
  name          = "${local.prefix}-sql-psa"
  purpose       = "VPC_PEERING"
  address_type  = "INTERNAL"
  address       = "10.61.0.0"
  prefix_length = 16
  network       = google_compute_network.app.id
}

resource "google_service_networking_connection" "sql" {
  network                 = google_compute_network.app.id
  service                 = "servicenetworking.googleapis.com"
  reserved_peering_ranges = [google_compute_global_address.sql_psa.name]
  deletion_policy         = "ABANDON"
}

resource "google_compute_address" "app" {
  name         = "${local.prefix}-app"
  region       = local.region
  address_type = "EXTERNAL"
  network_tier = "PREMIUM"
  depends_on   = [google_project_service.required]
  lifecycle { prevent_destroy = true }
}

resource "google_compute_firewall" "https" {
  name                    = "${local.prefix}-web-ingress"
  network                 = google_compute_network.app.name
  direction               = "INGRESS"
  source_ranges           = ["0.0.0.0/0"]
  target_service_accounts = [google_service_account.runtime.email]
  allow {
    protocol = "tcp"
    ports    = ["80", "443"]
  }
}

resource "google_compute_firewall" "iap_ssh" {
  name                    = "${local.prefix}-iap-ssh"
  network                 = google_compute_network.app.name
  direction               = "INGRESS"
  source_ranges           = ["35.235.240.0/20"]
  target_service_accounts = [google_service_account.runtime.email]
  allow {
    protocol = "tcp"
    ports    = ["22"]
  }
}
