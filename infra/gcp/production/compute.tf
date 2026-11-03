resource "google_compute_instance" "app" {
  name                = "${local.prefix}-app"
  zone                = var.zone
  machine_type        = var.app_machine_type
  deletion_protection = true
  # A reviewed resize can stop the VM; this is intentionally not a zero-downtime deployment.
  allow_stopping_for_update = true
  boot_disk {
    initialize_params {
      image = var.app_image
      type  = "pd-balanced"
      size  = 50
    }
  }
  network_interface {
    subnetwork = google_compute_subnetwork.app.id
    access_config {
      nat_ip       = google_compute_address.app.address
      network_tier = "PREMIUM"
    }
  }
  service_account {
    email  = google_service_account.runtime.email
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
  # Intentionally no app startup script / remote-exec. This root creates the infrastructure;
  # GCP runtime installation, metadata isolation, TLS and AI-off smoke are a separate gate.
  depends_on = [google_project_iam_member.runtime]
  lifecycle { prevent_destroy = true }
}
