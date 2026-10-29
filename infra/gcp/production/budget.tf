variable "budget" {
  description = "Reviewed project-only monthly alert budget. Currency must match the existing billing account; this is not a spending cap. No billing/IAM changes are implied."
  type = object({
    billing_account = string
    currency_code   = string
    monthly_amount  = number
  })
  default  = null
  nullable = true
  validation {
    condition = var.budget == null ? true : (
      can(regex("^[A-Z0-9]{6}-[A-Z0-9]{6}-[A-Z0-9]{6}$", var.budget.billing_account)) &&
      can(regex("^[A-Z]{3}$", var.budget.currency_code)) &&
      var.budget.monthly_amount > 0 && floor(var.budget.monthly_amount) == var.budget.monthly_amount
    )
    error_message = "Use the existing billing account ID, its verified ISO currency, and a positive whole-unit monthly amount."
  }
}

resource "google_project_service" "budget" {
  count              = var.budget == null ? 0 : 1
  service            = "billingbudgets.googleapis.com"
  disable_on_destroy = false
}

resource "google_billing_budget" "project" {
  count           = var.budget == null ? 0 : 1
  billing_account = var.budget.billing_account
  display_name    = "CAPSNOTE project monthly budget"
  deletion_policy = "PREVENT"
  budget_filter {
    projects               = ["projects/${var.project_number}"]
    calendar_period        = "MONTH"
    credit_types_treatment = "EXCLUDE_ALL_CREDITS"
  }
  amount {
    specified_amount {
      currency_code = var.budget.currency_code
      units         = tostring(var.budget.monthly_amount)
    }
  }
  dynamic "threshold_rules" {
    for_each = [0.5, 0.9, 1.0]
    content {
      threshold_percent = threshold_rules.value
      spend_basis       = "CURRENT_SPEND"
    }
  }
  threshold_rules {
    threshold_percent = 1.0
    spend_basis       = "FORECASTED_SPEND"
  }
  all_updates_rule {
    disable_default_iam_recipients   = true
    enable_project_level_recipients  = false
    monitoring_notification_channels = google_monitoring_notification_channel.email[*].name
  }
  depends_on = [google_project_service.budget]
  lifecycle {
    prevent_destroy = true
    precondition {
      condition     = var.notification_email != null
      error_message = "A real reviewed notification recipient is required for the budget."
    }
  }
}
