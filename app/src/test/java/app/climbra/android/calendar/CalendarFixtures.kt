package app.climbra.android.calendar

/** `plan.CalendarResponse` as the backend writes it, for the week of Monday 2026-11-09 (spec 005 tests). */
object CalendarFixtures {

    const val RIDERS = """[{"id":5,"name":"Anna","timeZone":"Europe/Warsaw"}]"""

    const val STEPS = """[
        {"repetitions":1,"steps":[{"kind":"WARMUP","durationSeconds":600,"powerLowPctFtp":50,"powerHighPctFtp":65,
          "powerLowWatts":130,"powerHighWatts":169,"cadenceLow":null,"cadenceHigh":null,"note":null,"zone":2}]},
        {"repetitions":5,"steps":[
          {"kind":"WORK","durationSeconds":240,"powerLowPctFtp":110,"powerHighPctFtp":120,"powerLowWatts":286,
           "powerHighWatts":312,"cadenceLow":95,"cadenceHigh":105,"note":"Seated","zone":5},
          {"kind":"RECOVERY","durationSeconds":180,"powerLowPctFtp":50,"powerHighPctFtp":50,"powerLowWatts":130,
           "powerHighWatts":130,"cadenceLow":null,"cadenceHigh":null,"note":null,"zone":1}]}]"""

    const val COMPLETED = """{"id":1,"planId":7,"date":"2026-11-10","title":"5×4 min VO2max","type":"VO2MAX",
        "indoor":true,"plannedDurationMinutes":60,"plannedTss":75,"plannedIf":0.86,"targetRpe":8,
        "description":"Hard but even.","blocks":$STEPS,"status":"COMPLETED","activityId":11,"matchSource":"AUTO",
        "comparison":{"level":"MODERATE","duration":{"planned":3600.0,"actual":3500.0,"ratio":0.97,"level":"OK"},
          "tss":{"planned":75.0,"actual":62.0,"ratio":0.83,"level":"MODERATE"},"intensityFactor":null,
          "intervals":{"plannedRepetitions":5,"detectedBlocks":4,"ratio":0.8,"level":"MODERATE"},"tssSource":"POWER"},
        "somethingNew":1}"""

    const val PLANNED = """{"id":2,"planId":7,"date":"2026-11-14","title":"Long endurance","type":"ENDURANCE",
        "indoor":false,"plannedDurationMinutes":180,"plannedTss":140,"plannedIf":0.68,"targetRpe":4,
        "description":"Zone 2.","blocks":[],"status":"PLANNED","activityId":null,"matchSource":null,"comparison":null}"""

    const val REST = """{"id":3,"planId":7,"date":"2026-11-12","title":"Rest day","type":"REST","indoor":false,
        "plannedDurationMinutes":0,"plannedTss":0,"plannedIf":0.0,"targetRpe":1,"description":"","blocks":[],
        "status":"REST","activityId":null,"matchSource":null,"comparison":null}"""

    const val RIDE_LINKED = """{"activityId":11,"date":"2026-11-10","startTimeLocal":"2026-11-10T08:00:00",
        "name":"Morning VO2","sportType":"road_biking","durationSeconds":3500,"distanceMeters":30000.0,
        "trainingStressScore":62.4,"trainingLoad":62.4,"trainingLoadSource":"POWER","intensityFactor":0.8,
        "normalizedPower":220.0,"intervalCount":4,"analysed":true,"plannedWorkoutId":1}"""

    const val RIDE_UNPLANNED = """{"activityId":12,"date":"2026-11-11","startTimeLocal":"2026-11-11T17:00:00",
        "name":null,"sportType":"road_biking","durationSeconds":1800,"distanceMeters":12000.0,
        "trainingStressScore":null,"trainingLoad":40.2,"trainingLoadSource":"HEART_RATE","intensityFactor":null,
        "normalizedPower":null,"intervalCount":null,"analysed":true,"plannedWorkoutId":null}"""

    const val PLAN = """{"id":7,"riderId":5,"conversationId":21,"name":"FTP build","goal":"Raise FTP",
        "methodology":"PYRAMIDAL","rationale":"Because.","startDate":"2026-11-02","endDate":"2026-11-29",
        "createdAt":"2026-10-27T08:00:00Z","updatedAt":"2026-10-27T08:00:00Z","revision":1,"adjustedAt":null,
        "weeks":[],"suggestion":{"id":4,"createdAt":"2026-11-11T08:00:00Z","reasons":[
          {"code":"WORKOUT_DEVIATION","date":"2026-11-10","plannedWorkoutId":1,"activityId":11,"weekNumber":null,
           "metric":"intervals","planned":5.0,"actual":4.0},
          {"code":"WEEK_LOAD_DEVIATION","date":"2026-11-02","plannedWorkoutId":null,"activityId":null,
           "weekNumber":1,"metric":"tss","planned":300.0,"actual":220.0}]},
        "pendingProposal":{"id":9,"conversationId":21,"createdAt":"2026-11-11T09:00:00Z","fromDate":"2026-11-14",
          "summary":"Easier."},
        "adjustmentRunning":false}"""

    const val WEEK = """{"start":"2026-11-09","plannedTss":215,"plannedSeconds":14400,"riddenTss":103,
        "riddenSeconds":5300,"planWeek":{"planId":7,"weekNumber":2,"focus":"Build","recoveryWeek":false}}"""

    fun calendar(
        from: String = "2026-11-09",
        to: String = "2026-11-15",
        plans: String = PLAN,
        workouts: String = "$COMPLETED,$REST,$PLANNED",
        rides: String = "$RIDE_LINKED,$RIDE_UNPLANNED",
        weeks: String = WEEK,
    ) = """{"from":"$from","to":"$to","plans":[$plans],"plannedWorkouts":[$workouts],"activities":[$rides],
           "weeks":[$weeks]}"""

    fun garmin(status: String = "CONNECTED", available: Boolean = true, sendable: Int = 3, stale: Int = 0) =
        """{"available":$available,"status":"$status","connectedAt":"2026-11-01T08:00:00Z","sendable":$sendable,
           "from":"2026-11-12","to":"2026-11-25","sent":2,"stale":$stale}"""

    const val SENT = """{"sent":3,"unchanged":1,"removed":2,"failed":[{"date":"2026-11-14","title":"Long endurance",
        "code":"X"}],"incomplete":true,"reason":"GARMIN_RATE_LIMITED"}"""
}
