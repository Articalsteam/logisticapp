package com.articals.logistics.ui.text

import com.articals.logistics.core.AppError

/**
 * Every user-facing string in the app.
 *
 * One object, grouped by area, so that adding a second language later is a matter of
 * adding one more object of the same shape and a lookup — not of hunting for English
 * text inside composables. Nothing in the interface writes a literal sentence; if a
 * string is missing here, it does not belong on screen.
 *
 * Convention: functions take the values they interpolate, never a pre-built string,
 * because word order differs between languages.
 */
object ArticalsStrings {

    /** Words that appear on nearly every screen. */
    object Common {
        const val byStudio = "by Articals Studio"
        const val appName = "Articals Logistics"
        const val retry = "Try again"
        const val cancel = "Cancel"
        const val close = "Close"
        const val save = "Save"
        const val saving = "Saving…"
        const val loading = "Loading…"
        const val back = "Back"
        const val search = "Search"
        const val clear = "Clear"
        const val confirm = "Confirm"
        const val done = "Done"
        const val edit = "Edit"
        const val delete = "Delete"
        const val refresh = "Refresh"
        const val share = "Share"
        const val copy = "Copy"
        const val copied = "Copied to the clipboard"
        const val yes = "Yes"
        const val no = "No"
        const val notAvailable = "Not available"
        const val optional = "optional"
        const val required = "required"
        const val seeAll = "See all"
        const val whyItMatters = "Why am I seeing this?"

        fun ofCount(count: Int, singular: String, plural: String): String =
            if (count == 1) "1 $singular" else "$count $plural"
    }

    /** The DEMO badge and everything that must never be mistaken for real data. */
    /**
     * The frame around every screen: the bars, the tabs and the two screens that
     * explain why there is nothing to show.
     */
    object Shell {
        const val tabToday = "Today"
        const val tabOrders = "Deliveries"
        const val tabPrice = "Price it"
        const val tabMore = "More"
        const val tabMyJobs = "My jobs"
        const val loadingJourney = "Getting your workspace ready…"
        const val signInAgain = "Sign in again"
        const val youAre = "Signed in as"
        const val company = "Company"
        const val switchCompany = "Switch company"
        const val switchCompanyBody = "You belong to more than one company; this is the one you are working in."
        const val notificationsBody =
            "Notices about your deliveries appear here while the app is open. " +
                "A real deployment also sends them as push notifications."
        const val noAccessTitle = "This screen is not for your role"
        const val noAccessBody =
            "The app hides screens a role cannot use, and refuses to open one if it is reached " +
                "any other way. Nothing is broken: ask an owner if you need this access."
        const val backToYourWork = "Back to your work"
        const val limitedChip = "Read-only"
        const val sectionWork = "Work"
        const val sectionMoney = "Money"
        const val sectionCompany = "Company"
        const val sectionThisBuild = "This build"
        const val support = "Support"
    }

    object Demo {
        const val badge = "DEMO"
        const val badgeLong = "DEMO — fictional data"
        const val bannerTitle = "Demo mode"
        const val bannerBody =
            "This build runs on fictional companies, riders and deliveries held in memory. " +
                "Nothing here is a real order, a real payment or a real message, and nothing is sent anywhere."
        const val simulatedPhoto = "Simulated photo — demo mode does not use the camera"
        const val simulatedNotification = "Simulated notification — no push was sent"
        const val openedWhatsApp = "Opened WhatsApp with the message ready to send"
        const val copiedMessage = "Message copied — paste it into WhatsApp to send it"
        const val neverClaimedSent = "Articals did not send this message for you"
        const val resetDone = "Demo data restored to its starting state"
        fun fakeAccount(password: String) = "Demo accounts use the password \"$password\""
    }

    object Config {
        const val firebaseMissingTitle = "Firebase is not configured"
        const val firebaseMissingBody =
            "This build was compiled for the real backend, but it was built without the Firebase " +
                "configuration files. Nothing is broken — the app simply has nothing to talk to."
        const val firebaseMissingStep1 = "Create a Firebase project and register both apps."
        const val firebaseMissingStep2 = "Put google-services.json in androidApp/ and GoogleService-Info.plist in iosApp/."
        const val firebaseMissingStep3 = "Rebuild. No code changes are needed."
        const val readSetupGuide = "Open docs/SETUP.md"
        const val demoInstead = "You can run the demo instead: build with -Particals.mode=demo."
        const val diagnosticsFooter = "Build details"
    }

    object Auth {
        const val signInTitle = "Sign in"
        const val signInSubtitle = "Manage deliveries, riders and pricing for your company."
        const val email = "Email address"
        const val password = "Password"
        const val displayName = "Your name"
        const val signIn = "Sign in"
        const val signingIn = "Signing in…"
        const val signOut = "Sign out"
        const val signOutConfirmTitle = "Sign out?"
        const val signOutConfirmBody = "You will need your email and password to sign back in."
        const val createAccount = "Create an account"
        const val registerTitle = "Create your account"
        const val registerSubtitle = "You will set up your company next."
        const val register = "Create account"
        const val registering = "Creating your account…"
        const val forgotPassword = "Forgot your password?"
        const val resetTitle = "Reset your password"
        const val resetBody =
            "We will send a reset link to your email address. For security we say the same thing " +
                "whether or not the address has an account."
        const val sendResetLink = "Send reset link"
        const val resetSentTitle = "Check your email"
        const val resetSentBody = "If that address has an account, a reset link is on its way."
        const val verifyTitle = "Verify your email address"
        const val verifyBody = "We sent a verification link to your email address. Open it, then come back here."
        const val verifyResend = "Send the link again"
        const val verifyCheck = "I have verified my email"
        const val verifyChecking = "Checking…"
        const val verifySimulated =
            "Demo mode has no inbox, so \"I have verified my email\" marks the address as verified. " +
                "A real deployment sends a link."
        const val haveAccount = "Already have an account? Sign in"
        const val needAccount = "New here? Create an account"
        const val useAccount = "Use"
        const val demoAccountsTitle = "One-tap demo accounts"
        const val demoAccountsBody =
            "Each account shows a different role, so you can see exactly what that person sees."
        const val passwordTooShort = "Use at least 8 characters."
        const val emailInvalid = "Enter a valid email address."
        const val signedOut = "Signed out"
    }

    object Workspace {
        const val chooseTitle = "Choose a workspace"
        const val chooseBody = "You belong to more than one company. Choose which one to work in."
        const val switch = "Switch workspace"
        const val noneTitle = "No company yet"
        const val noneBody =
            "Your account is not part of a company yet. Create one, or accept an invitation from a company you work with."
        const val createCompany = "Create a company"
        const val haveInvitation = "I have an invitation"
        const val invitationId = "Invitation id"
        const val invitationIdHint = "The id in the invitation link you were sent."
        const val companyName = "Company name"
        const val businessAddress = "Business address"
        const val serviceAreas = "Areas you deliver in"
        const val serviceAreasHint = "Separate them with commas. Used to suggest addresses, never to price a delivery."
        const val orderPrefix = "Order number prefix"
        const val orderPrefixHint = "Letters at the start of every order number, for example DEL."
        const val role = "Role"
        const val active = "Active"
        const val disabled = "Disabled"
    }

    object Onboarding {
        const val title = "Set up your company"
        const val body = "Four short steps. Everything can be changed later in company settings."
        const val stepCompany = "Company details"
        const val stepPricing = "Your rates"
        const val stepTeam = "Your team"
        const val stepDone = "Finish"
        const val next = "Continue"
        const val previous = "Back"
        const val finish = "Open my dashboard"
        const val skipped = "You can set this up later."
        const val placeholderRates =
            "These numbers are placeholders until you replace them with your own. Aricals never " +
                "chooses a rate for a company, and every one of them can be changed later."
        const val completedTitle = "You are ready"
        const val completedBody =
            "Create your first delivery, or invite your riders so they can see their jobs on their own phones."
    }

    /**
     * Customers.
     *
     * Plurals are written as functions rather than templates so a language that
     * inflects differently has somewhere to do it: `ordersCount(1)` is the place a
     * translator changes the whole sentence, not the word "orders".
     */
    object Customers {
        const val subtitle = "Everyone you deliver for, in one list."
        const val search = "Search by name, phone or email"
        const val activeChipLabel = "Active"
        const val archivedChipLabel = "Archived"
        fun activeChip(count: Int) = "Active ($count)"
        fun archivedChip(count: Int) = "Archived ($count)"
        fun ordersCount(count: Int) = if (count == 1) "1 delivery" else "$count deliveries"
        const val archiveConfirm = "Archive"
        const val restore = "Restore"
        const val editTitle = "Edit customer"
        fun archiveTitle(name: String) = "Archive $name?"
        const val archiveBody =
            "They stay on the deliveries they already have, and stop appearing when you create a new one."
        fun archived(name: String) = "$name was archived"
        fun restored(name: String) = "$name is active again"
        const val added = "Customer added"
        const val saved = "Customer saved"
        fun incomplete() = "A name and a phone number are needed before this can be saved."
        const val noMatches = "Nothing matches that search"
        const val noMatchesBody = "Try part of a name, a phone number or an email address."
        const val notFound = "That customer was not found in this workspace."
        const val customerSince = "Customer since"
        const val history = "What we have done for them"
        const val deliveries = "Deliveries"
        const val charged = "Charged in total"
        const val notes = "Notes"
        const val noOrders = "No deliveries yet"
        const val noOrdersBody = "When you create a delivery for them, it appears here."
    }

    /**
     * Riders.
     *
     * The wording keeps the record and the login apart: a rider exists as a person to
     * assign work to, and separately may have an account. Saying "invited" for the
     * first would be a lie, so the invite copy only ever claims a link was created.
     */
    object Riders {
        const val subtitle = "The people who carry your deliveries, and what they have ridden."
        const val search = "Search by name, phone or email"
        fun activeChip(count: Int) = "Working with you ($count)"
        fun inactiveChip(count: Int) = "Inactive ($count)"
        fun deliveredCount(count: Int) = if (count == 1) "1 delivery" else "$count deliveries"
        const val editTitle = "Edit rider"
        const val status = "Working status"
        const val plate = "Plate number"
        const val emailExplain = "An email address is what a rider signs in with."
        const val added = "Rider added"
        const val saved = "Rider saved"
        fun statusChanged(name: String) = "$name's status was changed"
        const val incomplete = "A name and a phone number are needed before this can be saved."
        const val noMatches = "Nothing matches that search"
        const val noMatchesBody = "Try part of a name, a phone number or an email address."
        const val notFound = "That rider was not found in this workspace."
        fun inviteTitle(name: String) = "Give $name a login"
        const val inviteBody =
            "Articals makes a one-time link. You send it however you like — Articals does not send it for you."
        const val inviteEmailInvalid = "That does not look like an email address."
        const val inviteReady = "Invitation ready to send"
        const val inviteLink = "The link to send"
        const val totals = "How they are doing"
        const val delivered = "Delivered"
        const val openJobs = "On the road now"
        const val failed = "Failed"
        const val paidToRider = "Paid to this rider"
        const val rating = "Rating"
        const val noJobs = "No deliveries yet"
        const val noJobsBody = "Assign a delivery to this rider and it appears here."
    }

    /**
     * Expenses.
     *
     * The split between running costs and delivery costs is the backend's own split, so
     * the words match what the reports will say.
     */
    object Expenses {
        const val subtitle = "Fuel, rent, salaries and everything else the company spends."
        const val totals = "This period"
        const val total = "Everything together"
        const val kind = "What kind of cost is it?"
        const val description = "More detail (optional)"
        const val dateFormat = "YYYY-MM-DD"
        const val dateInvalid = "Use the YYYY-MM-DD order, for example 2026-03-14."
        const val amountInvalid = "That is not an amount."
        const val orderId = "Which delivery?"
        const val orderIdExplain = "A delivery cost has to name the delivery it belongs to."
        const val added = "Expense recorded"
        const val deleted = "Expense removed"
        const val incomplete = "An amount above zero and a date are needed before this can be saved."
        const val deleteTitle = "Remove this expense?"
        fun deleteBody(amount: String, category: String) =
            "Removing the $category expense of $amount keeps it in the audit log, so the books still show what happened."
        const val deleteReasonRequired = "A reason is needed: removing an expense is recorded."
    }

    /**
     * The company's own rate card.
     *
     * Every refusal here is worded as the server words it, because they are the same
     * rule: an open-ended band, two bands ending at the same distance, a zone method
     * with no zones, a rider method with nothing to cost with.
     */
    object PricingSettings {
        const val title = "Rates"
        const val subtitle = "What you charge, and what you pay the rider. Saved as a new version each time."
        const val versionLabel = "Version"
        const val name = "What to call these rates"
        const val missing = "This company has no rate card yet. Contact Articals Studio if that is unexpected."
        const val savedWithVersion = "Rates saved as a new version"
        const val saveFailed = "The rates could not be saved"
        const val discard = "Discard changes"
        const val unsaved = "You have changes that are not saved yet."
        const val problemsTitle = "Before these rates can be saved"
        const val nameRequired = "Give the rate card a name."
        const val methodTitle = "How you calculate a price"
        const val bandsRequired = "Distance-band pricing needs at least one band."
        const val zoneRulesRequired = "Area-to-area pricing needs at least one area price."
        const val perKmRequired = "This method needs a rate per kilometre above zero."
        const val maximumBelowMinimum = "The maximum charge cannot be below the minimum charge."
        const val roundingTooLarge = "The rounding step has to be ₦1,000 or less."
        const val marginTooHigh = "The target margin has to be below 100%."
        const val bandsLabel = "distance bands"
        const val riderBandsLabel = "rider distance bands"
        fun multipleCatchAll(label: String) = "Only one of the $label may be open-ended."
        fun duplicateBounds(label: String) = "Two $label end at the same distance."
        fun bandIncomplete(label: String) = "Every one of the $label needs an amount, and a distance unless it is open-ended."
        const val riderRateRequired = "Distance-rate rider costing needs a rate per kilometre."
        const val riderBandsRequired = "Distance-band rider costing needs at least one band."
        const val riderZonesRequired = "Area-based rider costing needs at least one area price."
        const val riderFixedRequired = "A fixed rider fee needs the fee itself."
        const val riderHistoryRequired = "A historical average needs a number; record completed deliveries first."
        const val surchargeLabelRequired = "Every surcharge needs a name."
        fun surchargeAmountRequired(label: String) = "The surcharge \"$label\" needs an amount above zero."
        fun surchargePercentRequired(label: String) = "The surcharge \"$label\" needs a percentage above zero."
        const val openEndedBand = "Open-ended (anything further)"
        const val addArea = "Add an area price"
        const val areaFrom = "From"
        const val areaTo = "To"
        const val kind = "Fixed or percentage"
        const val serviceLevelsHint = "Leave empty to apply to every kind of delivery."
        const val targetProfit = "What profit are you aiming for?"
        const val targetProfitExplain =
            "Optional. A target is advice: the app says when a delivery misses it, and never refuses a price for missing it."
        const val zoneMissingFallback = "If there is no price for an area pair"
        const val useDistanceInstead = "Fall back to the distance rate"
        const val refuseInstead = "Refuse the price and ask"
        const val versioningNote =
            "A saved rate card is a version. Deliveries already quoted keep the price they were quoted at."
        const val riderCostMethod = "How you work out what to pay the rider"
        const val riderMinimumCost = "Never pay less than"
        const val riderHistoricalAverage = "Average from recent deliveries"
    }

    object Pricing {
        const val title = "Price calculator"
        const val subtitle = "Every figure below comes from your own rate card."
        const val pickup = "Pickup"
        const val destination = "Destination"
        const val address = "Address"
        const val area = "Area"
        const val contactName = "Contact name"
        const val contactPhone = "Contact phone"
        const val distance = "Distance"
        const val distanceManual = "Distance (km)"
        const val distanceExplained =
            "Enter the road distance yourself. Aricals does not invent a distance, and it never " +
                "estimates one from a map pin."
        const val serviceLevel = "Service level"
        const val additionalCosts = "Extra costs"
        const val addCost = "Add a cost"
        const val costLabel = "What is it for?"
        const val costAmount = "Amount"
        const val calculate = "Calculate price"
        const val calculating = "Calculating…"
        const val recalculate = "Recalculate"
        const val customerCharge = "Customer pays"
        const val riderCost = "Rider is paid"
        const val additionalTotal = "Extra costs"
        const val profit = "Your profit"
        const val margin = "Margin"
        const val breakdown = "How this price was built"
        const val warnings = "Before you accept this price"
        const val noWarnings = "No warnings. This price is ready to use."
        const val blocked = "This quote cannot be used yet"
        const val saveQuote = "Save this quote"
        const val quoteSaved = "Quote saved"
        const val createOrder = "Create the delivery"
        const val history = "Saved quotes"
        const val historyEmpty = "No saved quotes yet"
        const val historyEmptyBody = "Prices you save appear here, with what you charged and what you earned."
        const val riderCostAdjust = "Adjust the rider's cost"
        const val riderCostExplain =
            "The rate card gives an estimate. Enter what you have actually agreed with the rider."
        const val amount = "Amount"
        const val reason = "Reason"
        const val reasonRequired = "Say why you are changing this. It is recorded on the quote."
        const val overridePrice = "Set a different price"
        const val overrideBelowMinimum = "This is below your minimum charge"
        const val overrideNeedsPermission = "Your role cannot override a price."
        const val needsQuotePermission = "Your role cannot price deliveries."
        const val createNeedsPermission = "Your role cannot create a delivery."
        const val overrideNeedsPermissionExplain =
            "Only the owner and the manager can set a price that is not the one your rates produce. " +
                "Ask them to do it, or price this delivery with the rates."
        const val targetMargin = "Target margin"
        const val targetProfit = "Target profit"
        const val noMatchingBand = "This distance does not match any of your bands."
        const val zoneRuleMissing = "There is no rate for this area pair yet."
        const val distanceRequired = "A distance is needed before this can be priced."
        const val manualDistanceReview = "This distance was entered by hand; check it before you rely on it."
        const val zeroCustomerCharge = "This price charges the customer nothing."
        const val priceBelowCost = "This price is below what the delivery costs you."
        const val noCustomer = "No customer record"
        const val noCustomerExplain =
            "The delivery is recorded with the name and number above, without creating a customer."
        const val existingCustomer = "A customer you already have"
        const val newCustomer = "Add this customer"
        const val quoteHistoryTitle = "Saved quotes"
        const val quoteHistoryBody =
            "Every quote you saved, newest first. Open one to see what it was or to price something similar."
        const val useThisAgain = "Use these details again"
        const val blockedHint =
            "Acknowledging every warning is what lets this price be used. It is recorded on the quote."

        fun savedAt(whenText: String) = "Saved $whenText"
        fun versionChanged(from: Int, to: Int) = "Your rates changed (version $from to $to). Recalculate before quoting."
    }

    object Orders {
        const val title = "Deliveries"
        const val newOrder = "New delivery"
        const val empty = "No deliveries yet"
        const val emptyBody = "Create the first delivery and it will appear here, on the rider's phone and on the customer's tracking page."
        const val emptySearch = "Nothing matches that search"
        const val emptySearchBody = "Try a different order number, customer name or area."
        const val emptyFilter = "No deliveries in this filter"
        const val emptyFilterBody = "Clear the filter to see everything."
        const val searchHint = "Order number, customer or area"
        const val sort = "Sort"
        const val sortNewest = "Newest first"
        const val sortOldest = "Oldest first"
        const val sortHighestValue = "Highest value"
        const val sortStatus = "By status"
        const val filter = "Filter"
        const val filterAll = "All"
        const val filterOpen = "Open"
        const val filterNeedsAttention = "Needs attention"
        const val filterToday = "Today"
        const val loadMore = "Load more"
        const val showingAll = "That is everything."
        const val orderNumber = "Order number"
        const val customer = "Customer"
        const val rider = "Rider"
        const val status = "Status"
        const val unassigned = "Unassigned"
        const val assigned = "Assigned"
        const val created = "Created"
        const val scheduled = "Scheduled for"
        const val description = "What is being delivered"
        const val itemCategory = "What kind of item"
        const val note = "Note (not shown to the customer)"
        const val assignRider = "Assign a rider"
        const val reassign = "Change the rider"
        const val unassign = "Remove the rider"
        const val assign = "Assign"
        const val assignmentReason = "Reason (recorded in the audit log)"
        const val timeline = "What has happened"
        const val noEvents = "Nothing recorded yet"
        const val updateStatus = "Update the delivery"
        const val allowedNext = "The next step in the delivery"
        const val correctionLabel = "Correcting a finished delivery"
        const val correctionExplain =
            "This delivery is already finished. Correcting it needs a written reason, and it " +
                "changes the reports it was counted in."
        const val failureReason = "What went wrong"
        const val actuals = "Final amounts"
        const val actualCharge = "What the customer finally paid"
        const val actualRiderCost = "What the rider was paid"
        const val actualExtraCosts = "Extra costs, final"
        const val recordActuals = "Record final amounts"
        const val actualsRecorded = "Final amounts recorded"
        const val orderCreated = "The delivery is created and appears in the list"
        const val createdFromQuote = "Created from a saved quote"
        const val actualsExplain =
            "Record what really happened. The estimate stays on the delivery so the difference " +
                "between quoted and actual profit stays visible."
        const val estimate = "Estimate"
        const val actual = "Actual"
        const val variance = "Difference"
        const val trackingLink = "Customer tracking link"
        const val copyLink = "Copy the link"
        const val shareLink = "Share the tracking link"
        const val openWhatsApp = "Send the link on WhatsApp"
        const val printLabel = "Print a delivery slip"
        const val cancelOrder = "Cancel this delivery"
        const val cancelReason = "Why is it being cancelled?"
        const val cancelConfirm = "Cancel the delivery"
        const val reopenedWarning = "Reopening a finished delivery changes your reports."

        const val notFound = "That delivery no longer exists."
        const val statusUpdated = "Delivery updated"
        const val riderAssigned = "A rider now has this delivery"
        const val riderRemoved = "The rider was taken off this delivery"
        const val route = "Pickup and drop-off"
        const val pickup = "Pickup"
        const val dropoff = "Drop-off"
        const val distance = "Distance"
        const val distanceManual = "The distance was entered by hand and has not been checked against a route."
        const val distanceProvider = "Estimated from the delivery addresses."
        const val contact = "Contact"
        const val call = "Call"
        const val openInMaps = "Open in maps"
        const val shareTitle = "The customer's tracking link"
        const val shareBody =
            "The message is written out for you. This app does not send it: it opens the share " +
                "sheet, or copies the text so you can paste it yourself."
        const val copyMessage = "Copy the message"
        const val viewMessage = "Read the message"
        const val money = "Money on this delivery"
        const val estimatedRiderCost = "Expected rider cost"
        const val additionalCosts = "Extra costs"
        const val profit = "Profit"
        const val margin = "Margin"
        const val noActuals = "Final amounts have not been recorded."
        const val failureTitle = "Record a failed delivery"
        const val failureNotes = "What happened (shown in the timeline)"
        const val statusNotes = "Anything to add to the timeline"
        const val cancelReasonRequired = "A cancellation needs a written reason."
        const val nothingToDo = "This delivery is finished. Nothing further is needed."
        const val sectionActions = "What you can do"
        const val sectionDetails = "The delivery"
        const val sectionMoney = "What it is worth"

        fun createdOn(date: String) = "Created $date"
        fun pickedUpBy(name: String) = "Picked up by $name"
        fun assignedTo(name: String) = "Assigned to $name"
        fun ordersCount(count: Int) = if (count == 1) "1 delivery" else "$count deliveries"
        fun needsAttention(count: Int) = if (count == 1) "1 needs attention" else "$count need attention"
    }

    object Tracking {
        const val title = "Track a delivery"
        const val reference = "Tracking reference"
        const val referenceHint = "For example ART-K7M2PQ4XZ"
        const val lastFourDigits = "Last four digits of the phone number used"
        const val lastFourExplain =
            "This company asks for the last four digits of the customer's phone number before showing a delivery."
        const val track = "Track"
        const val lookupBody = "Share the reference with the customer, or search it here on their behalf."
        const val notFoundTitle = "No delivery with that reference"
        const val notFound = "No delivery with that reference"
        const val notFoundBody =
            "Check the reference. If it still does not work, ask the company that is making the delivery."
        const val currentStatus = "Where your delivery is now"
        const val history = "How it got here"
        const val lastUpdated = "Last updated"
        const val inAppTitle = "Tracking (what the customer sees)"
        const val inAppBody =
            "This is exactly the page the customer sees from the tracking link. It never shows your " +
                "prices, your notes or the rider's details."
        const val shareOpened = "Opened the share sheet"
    }

    object Rider {
        const val problemReported = "Reported. The office can see it"
        const val title = "My deliveries"
        const val empty = "No deliveries assigned to you"
        const val emptyBody = "When your dispatcher assigns you a job, it appears here."
        const val today = "Today"
        const val open = "Open"
        const val finished = "Finished"
        const val start = "Start the delivery"
        const val pickedUp = "I have picked it up"
        const val inTransit = "On my way"
        const val delivered = "Delivered"
        const val failed = "Could not deliver"
        const val failureReason = "What happened?"
        const val proofTitle = "Proof of delivery"
        const val proofTake = "Take the photo"
        const val proofRetake = "Take it again"
        const val proofSimulated =
            "Demo mode uses a placeholder image instead of the camera so this step can be tried " +
                "without a permission prompt. A real build asks for the camera here."
        const val proofRequired = "A photo is needed to mark this delivery delivered."
        const val reportProblem = "Report a problem"
        const val problemTitle = "What is wrong?"
        const val problemHint = "Describe it in your own words. Your dispatcher will see this."
        const val problemSend = "Record the problem"
        const val problemSent = "The delivery is marked as failed and the reason is on it"
        const val problemBody =
            "A problem is recorded by marking the delivery as failed with the reason, so the " +
                "dispatcher sees it on the delivery instead of having to remember a phone call."
        const val problemExplain =
            "Choose what went wrong. The delivery is failed with that reason, which is exactly " +
                "what your dispatcher needs to decide what happens next."
        const val callDispatcher = "Call the dispatcher"
        const val callCustomer = "Call the customer"
        const val navToPickup = "Open the pickup in maps"
        const val navToDestination = "Open the destination in maps"
        const val earnings = "What you have earned"
        const val noProfile = "This login has no rider record yet"
        const val noProfileBody =
            "Your account is not linked to a rider in this company. Ask your dispatcher to add " +
                "you, or sign in with one of the demo rider accounts."
        const val proofUploaded = "The photo is saved against this delivery"
        const val sendProofThenDeliver =
            "Take the photo first: a delivered delivery is expected to have proof on it."
        const val jobDetail = "The job"
        const val whatHappensNext = "What you can do"
        const val noEarnings = "Recorded once your dispatcher confirms the amounts."
    }

    object Dashboard {
        const val title = "Dashboard"
        const val deliveriesToday = "Delivered today"
        const val openNow = "In progress now"
        const val needsAttention = "Needs attention"
        const val waitingAssignment = "Waiting for a rider"
        const val revenue = "Charged to customers"
        const val profit = "Profit"
        const val estimatedProfit = "Estimated profit"
        const val actualProfit = "Actual profit"
        const val awaitingFinalAmounts = "Waiting for final amounts"
        const val trend = "Last 14 days"
        const val dataQuality = "About these numbers"
        const val missingDays = "Days with no deliveries are shown as zero, not skipped."
        const val hasUnfinalized = "Some finished deliveries do not have their final amounts recorded yet."
        const val pricingChanged = "Your rates changed during this period, so the older deliveries keep the price they were quoted at."
        const val noFinancialAccess =
            "Your role does not include financial reports. The operational counts below are still yours."
        const val attentionTitle = "Needs attention"
        const val attentionEmpty = "Nothing needs attention. Every delivery has what it needs."
    }

    /**
     * The user-facing label for an "needs attention" reason code.
     *
     * The vocabulary lives in `domain.AttentionReasons` and is deliberately separate
     * from the copy: a screen may show a reason the device has never heard of, and the
     * raw code is a better thing to display than nothing.
     */
    fun attentionLabel(reason: String): String = when (reason) {
        com.articals.logistics.domain.AttentionReasons.Unassigned -> Attention.unassigned
        com.articals.logistics.domain.AttentionReasons.RiderCostUnconfirmed -> Attention.riderCostUnconfirmed
        com.articals.logistics.domain.AttentionReasons.ManualDistanceReview -> Attention.manualDistanceReview
        com.articals.logistics.domain.AttentionReasons.AwaitingFinalAmounts -> Attention.awaitingFinalAmounts
        com.articals.logistics.domain.AttentionReasons.UnreportedProblem -> Attention.unreportedProblem
        com.articals.logistics.domain.AttentionReasons.DeliveryFailed -> Attention.deliveryFailed
        else -> reason
    }

    object Attention {
        const val unassigned = "No rider assigned"
        const val riderCostUnconfirmed = "Rider's cost not confirmed"
        const val manualDistanceReview = "Distance entered by hand"
        const val awaitingFinalAmounts = "Final amounts not recorded"
        const val unreportedProblem = "A rider reported a problem"
        const val deliveryFailed = "Delivery failed"
    }

    object Share {
        const val openedWhatsApp = "Opened the share sheet — choose WhatsApp to send it"
        const val copied = "Copied. Paste it into WhatsApp or any other app"
        const val notSent = "Articals did not send this message for you"
        const val couldNotShare = "This device has no app to share with. The text is below."
        fun trackingMessage(
            companyName: String,
            orderNumber: String,
            link: String?,
            isDemo: Boolean,
        ): String = buildString {
            append(companyName)
            append(": delivery ")
            append(orderNumber)
            if (link != null) {
                append(". Track it here: ")
                append(link)
            } else {
                append(" is on its way. Ask us for the tracking link.")
            }
            if (isDemo) {
                append(" (DEMO message — this link does not exist outside the demo.)")
            }
        }
    }

    object Settings {
        const val title = "Settings"
        const val currency = "Currency"
        const val company = "Company"
        const val companyDetails = "Company details"
        const val team = "Staff and permissions"
        const val teamEmpty = "Just you so far"
        const val teamEmptyBody = "Invite a dispatcher or a manager and give them exactly the access they need."
        const val invite = "Invite someone"
        const val inviteEmail = "Their email address"
        const val inviteRole = "What may they do?"
        const val inviteSend = "Send the invitation"
        const val inviteSent = "Invitation recorded"
        const val inviteLinkBody =
            "Demo mode has no inbox. The invitation is real inside this demo: sign in as that " +
                "email address to accept it."
        const val roleOwner = "Owner — everything, including billing"
        const val roleManager = "Manager — everything except staff and company settings"
        const val roleDispatcher = "Dispatcher — deliveries, no financial reports"
        const val roleRider = "Rider — only their own assigned deliveries"
        const val permissions = "Fine-grained permissions"
        const val permissionsBody = "Override the role defaults for this person only."
        const val changeRole = "Change role"
        const val disable = "Disable access"
        const val enable = "Enable access"
        const val disabledBody = "They keep their history but cannot sign in to this company."
        const val lastOwner = "A company must always have at least one owner."
        const val riders = "Riders"
        const val ridersEmpty = "No riders yet"
        const val ridersEmptyBody = "Add your riders so you can assign deliveries to them."
        const val addRider = "Add a rider"
        const val riderName = "Full name"
        const val riderPhone = "Phone number"
        const val riderEmail = "Email (needed for their login)"
        const val riderVehicle = "Vehicle"
        const val riderNotes = "Notes for the dispatcher"
        const val inviteRider = "Give them a login"
        const val riderInviteBody =
            "This link lets the rider create their own login and see only their own deliveries."
        const val riderInviteCopied = "Invitation link copied"
        const val riderHasLogin = "Has a login"
        const val riderNoLogin = "No login yet"
        const val customers = "Customers"
        const val customersEmpty = "No customers yet"
        const val customersEmptyBody = "Add the businesses and people you deliver for."
        const val addCustomer = "Add a customer"
        const val customerName = "Name"
        const val customerPhone = "Phone number"
        const val customerEmail = "Email address"
        const val customerType = "Person or business"
        const val individual = "Person"
        const val business = "Business"
        const val pricing = "Pricing and rates"
        const val pricingSubtitle = "Your four ways of pricing, your rates and your rider costs."
        const val expensesSubtitle = "Fuel, rent, salaries and everything else the company spends."
        const val reportsSubtitle = "Download your deliveries, finances, riders and customers as CSV."
        const val companySubtitle = "Name, address, service areas and how your orders are numbered."
        const val pricingMethod = "How you calculate a price"
        const val pricingMethodBase = "Base charge plus a rate per kilometre"
        const val pricingMethodBands = "Distance bands"
        const val pricingMethodZones = "Area to area"
        const val flatRateHint =
            "For one price whatever the distance, use distance bands and a single band with no upper limit."

        const val pricingMethodHybrid = "A mix of the above"
        const val baseCharge = "Base charge"
        const val perKm = "Charge per kilometre"
        const val minimumCharge = "Minimum charge"
        const val maximumCharge = "Maximum charge (optional)"
        const val rounding = "Rounding"
        const val roundingNone = "Do not round"
        const val roundingNearest = "Round to the nearest"
        const val roundingUp = "Always round up"
        const val roundingDown = "Always round down"
        const val roundingIncrement = "Round to the nearest"
        const val bands = "Distance bands"
        const val bandUpTo = "Up to"
        const val bandCharge = "Charge"
        const val bandOpen = "Anything longer"
        const val addBand = "Add a band"
        const val zones = "Areas"
        const val zoneRules = "Area to area rates"
        const val zoneFrom = "From"
        const val zoneTo = "To"
        const val addZoneRule = "Add an area pair"
        const val surcharges = "Surcharges"
        const val addSurcharge = "Add a surcharge"
        const val surchargeLabel = "What is it for?"
        const val surchargeAmount = "Amount"
        const val surchargePercent = "Percentage"
        const val succhargeKind = "Fixed or percentage"
        const val appliesTo = "Applies to"
        const val riderCost = "What you pay the rider"
        const val riderCostMethod = "How you work it out"
        const val riderCostFixed = "A fixed fee"
        const val riderCostPerKm = "A rate per kilometre"
        const val riderCostBands = "Distance bands"
        const val riderCostZone = "By area"
        const val riderCostAverage = "A historical average"
        const val riderCostManual = "The dispatcher enters it each time"
        const val riderFixedFee = "Fee per delivery"
        const val targetMargin = "Target profit margin"
        const val targetMarginExplain =
            "Used to warn you when a delivery is priced below what you want to earn. It never " +
                "changes a price by itself."
        const val saveRates = "Save these rates"
        const val ratesSaved = "Rates saved as version"
        const val ratesVersionBody = "Older deliveries keep the price they were quoted at."
        const val version = "Version"
        const val expenses = "Expenses"
        const val expensesEmpty = "No expenses recorded"
        const val expensesEmptyBody = "Record fuel, rent, salaries and anything else the company spends."
        const val addExpense = "Record an expense"
        const val expenseCategory = "Category"
        const val expenseAmount = "Amount"
        const val expenseDate = "Date"
        const val expenseLabel = "What was it for?"
        const val expenseDelete = "Remove this expense"
        const val expenseDeleteReason = "Why are you removing it?"
        const val operatingExpenses = "Running costs"
        const val deliveryExpenses = "Delivery costs"
        const val subscription = "Subscription"
        const val subscriptionStatus = "Your plan and access"
        const val diagnostics = "Demo controls"
        const val diagnosticsBody = "Show every loading, empty, error and offline state on purpose."
        const val about = "About"
        const val aboutBody = "Articals Logistics by Articals Studio."
        const val reports = "Reports"
        const val reportsBody = "Download your deliveries, finances, riders and customers as CSV."
        const val exportReport = "Download the CSV"
        const val exporting = "Building the report…"
        const val exported = "Report ready"
        const val exportRows = "rows"
        const val exportTruncated = "The report was capped at 5,000 rows. Narrow the dates for the rest."
        const val exportFailed = "The report could not be built"
        const val notifications = "Notifications"
        const val notificationsEmpty = "Nothing yet"
        const val language = "Language"
        const val languageEnglish = "English"
        const val languageNote = "More languages can be added without changing any screen."
    }

    object Subscription {
        const val title = "Subscription"
        const val trialing = "Free trial"
        const val activePaid = "Active"
        const val activeOffline = "Active — paid offline"
        const val activeBypass = "Active — access granted by Articals"
        const val grace = "Trial ended — grace period"
        const val limited = "Read-only"
        const val suspended = "Suspended"
        const val daysLeft = "days left"
        const val dayLeft = "day left"
        const val ended = "ended"
        const val renewTitle = "How to keep your access"
        const val renewBody =
            "Aricals Studio records your payment and renews your access. This app never takes a " +
                "payment and never sells you a subscription."
        const val contactUs = "Contact Articals Studio"
        const val readOnlyTitle = "This workspace is read-only"
        const val readOnlyBody =
            "You can still see everything and export your reports. Creating or changing deliveries, " +
                "staff or rates needs an active subscription."
        const val plan = "Plan"
        const val price = "Price"
        const val periodEnds = "Period ends"
        const val lastPayment = "Last payment recorded"
        const val noPayment = "No payment recorded yet"
        const val paidOffline = "Recorded offline by Articals Studio"
        const val bypassActive = "Aricals Studio has granted access until"
        const val trialEndsOn = "Free trial ends"

        /**
         * The wording the customer sees for each entitlement state.
         *
         * The states themselves are computed by the backend (`domain.EntitlementStates`);
         * this is only how they read, so the same state cannot be described two
         * different ways on two screens.
         */
        fun labelFor(state: String): String = when (state) {
            "trialing" -> trialing
            "active_paid" -> activePaid
            "active_offline" -> activeOffline
            "active_bypass" -> activeBypass
            "grace" -> grace
            "limited" -> limited
            "suspended" -> suspended
            else -> state
        }
    }

    object Admin {
        const val title = "Platform console"
        const val overview = "Overview"
        const val overviewBody = "Every company on the platform, what they pay and what is expiring."
        const val companies = "Companies"
        const val payments = "Payments"
        const val settings = "Platform settings"
        const val audit = "Audit log"
        const val companiesTitle = "Companies on the platform"
        const val searchHint = "Company name or id"
        const val filterStatus = "Access"
        const val noCompanies = "No companies match"
        const val noCompaniesBody = "Clear the search to see every company."
        const val total = "Companies"
        const val onboardingIncomplete = "Still setting up"
        const val upcomingExpiries = "Expiring soon"
        const val noExpiries = "Nothing expires in the next fortnight"
        const val activeBypasses = "Access grants"
        const val noBypasses = "No company has an access grant"
        const val revenueAutomated = "Paid through billing"
        const val revenueOffline = "Recorded offline"
        const val nonRevenue = "Trials and grants (not revenue)"
        const val revenueNote = "Only money actually received is counted."
        const val recordOfflinePayment = "Record an offline payment"
        const val amount = "Amount received"
        const val method = "How it was received"
        const val reference = "Bank, POS or transfer reference"
        const val referenceExplain = "So this payment can be checked against the bank statement later."
        const val periodStart = "Covers from"
        const val periodEnd = "Covers until"
        const val reason = "Why is this being recorded?"
        const val internalNotes = "Notes only Articals Studio sees"
        const val evidence = "Attach an image of the receipt"
        const val recordPayment = "Record the payment"
        const val paymentRecorded = "Payment recorded"
        const val grantBypass = "Grant access without payment"
        const val bypassReason = "Why is access being granted?"
        const val bypassUntil = "Grant access until"
        const val bypassMaxDays = "days is the longest an ordinary administrator can grant"
        const val bypassElevated = "That is longer than usual, and needs an elevated permission."
        const val bypassReplace = "This company already has a grant. Replace it?"
        const val bypassGranted = "Access granted"
        const val revokeBypass = "Revoke the access grant"
        const val revokeReason = "Why is it being revoked?"
        const val revokeAlsoSuspend = "Also suspend the company"
        const val bypassRevoked = "Access grant revoked"
        const val manageSubscription = "Change the subscription"
        const val actionActivate = "Activate"
        const val actionSuspend = "Suspend"
        const val actionReinstate = "Reinstate"
        const val actionExtend = "Extend the period"
        const val actionChangePlan = "Change the plan"
        const val actionSetTrialEnd = "Set the trial end"
        const val extendDays = "How many days?"
        const val choosePlan = "Which plan?"
        const val applyChange = "Apply the change"
        const val planCatalogue = "Plans"
        const val planCatalogueBody =
            "These prices are what this app shows everywhere. No screen has a price written into it."
        const val planName = "Name"
        const val planPrice = "Price per period"
        const val planPeriod = "Days per period"
        const val planTrial = "Trial days"
        const val planFeatures = "What it includes"
        const val savePlans = "Save the plan catalogue"
        const val plansSaved = "Plans saved"
        const val entitlementPolicy = "Entitlement rules"
        const val entitlementPolicyBody =
            "Trial length, grace period and how long an access grant may last. Applied by the backend, not by this app."
        const val policyTrialDays = "Trial days"
        const val policyGraceDays = "Grace days after a trial or paid period ends"
        const val policyBypassMaxDays = "Longest access grant"
        const val policyStandardPeriodDays = "Days in a paid period"
        const val savePolicy = "Save the rules"
        const val policySaved = "Rules saved"
        const val ledger = "Ledger"
        const val ledgerEmpty = "No ledger entries for this company"
        const val auditTitle = "Audit log"
        const val auditEmpty = "No entries"
        const val auditNotAvailableHere =
            "The audit log and the ledger are not read from the app against the real backend: they " +
                "live in records the app is not allowed to read, by design. Use the Firebase console " +
                "or the runbook command. In demo mode this panel shows the demo store's own entries."
        const val actor = "Who"
        const val action = "What"
        const val at = "When"
        const val resource = "Record"
        const val entryReason = "Reason"
        const val noAdminAccess = "This build has no platform console"
        const val noAdminAccessBody =
            "The console is compiled into the admin build only. Nothing is missing from your company build."
    }

    object Errors {
        const val offlineTitle = "You are offline"
        const val offlineBody = "The last change was not sent. We will keep everything you typed on this screen."
        const val retryHint = "Trying again usually works."
        const val permissionTitle = "Not allowed"
        const val notFoundTitle = "Not found"
        const val rejectedTitle = "That cannot be done"
        const val invalidTitle = "Check these fields"
        const val serverTitle = "Something went wrong on our side"
        const val unsupportedTitle = "Not available yet"
        const val unexpectedTitle = "Something went wrong"
        const val showDetails = "Technical details"
        const val hideDetails = "Hide details"
        const val errorCode = "Code"

        /** A title for any error, without the error's own (already user-safe) message. */
        fun titleFor(error: AppError): String = when (error) {
            is AppError.Offline -> offlineTitle
            is AppError.Unauthenticated -> "Your session ended"
            is AppError.NotAMember -> permissionTitle
            is AppError.PermissionDenied -> permissionTitle
            is AppError.NotFound -> notFoundTitle
            is AppError.InvalidInput -> invalidTitle
            is AppError.Rejected -> rejectedTitle
            is AppError.EntitlementLimited -> Subscription.readOnlyTitle
            is AppError.AlreadyExists -> "Already there"
            is AppError.Server -> serverTitle
            is AppError.Unsupported -> unsupportedTitle
            is AppError.Unknown -> unexpectedTitle
        }
    }

    object Diagnostics {
        const val title = "Demo controls"
        const val intro =
            "These controls exist only in a demo build. They make every state a real user might hit " +
                "appear on demand, instead of hoping to catch it."
        const val network = "Simulated network"
        const val networkNormal = "Normal"
        const val networkSlow = "Slow (3×)"
        const val networkOffline = "Offline"
        const val networkFlaky = "Unreliable (1 in 4 fails)"
        const val latency = "Delay each call by"
        const val latencyInstant = "No delay"
        const val forceError = "Make the next call fail"
        const val forceErrorNone = "Nothing forced"
        const val forceOffline = "Offline error"
        const val forcePermission = "Not-allowed error"
        const val forceServer = "Server error"
        const val forceRejected = "Business-rule refusal"
        const val forceValidation = "Validation error"
        const val entitlement = "Subscription state"
        const val entitlementDerived = "As the data says"
        const val emptyCompany = "Load an empty company"
        const val emptyCompanyBody = "Keeps the workspace and its owner, removes every delivery, rider and customer."
        const val reset = "Reset the demo data"
        const val resetConfirmTitle = "Reset the demo?"
        const val resetConfirmBody = "Every change you have made in this demo will be discarded."
        const val appearance = "Appearance"
        const val darkMode = "Dark mode"
        const val darkModeSystem = "Follow the device"
        const val darkModeOn = "Always dark"
        const val darkModeOff = "Always light"
        const val fontScale = "Text size"
        const val fontScaleSmall = "Small"
        const val fontScaleNormal = "Normal"
        const val fontScaleLarge = "Large"
        const val fontScaleHuge = "Largest"
        const val forcedErrorHint = "The next call fails, then this clears itself."
        const val signedInAs = "Signed in as"
        const val workspace = "Workspace"
        const val entitlementNow = "Access is currently"
        const val buildFooter = "Build"
        const val noFirebase = "Firebase is not initialised in this build."
    }
}
