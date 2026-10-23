import commands.ApiGet
import commands.ApiPut
import commands.AutoPair
import commands.DeleteEvent
import commands.GenerateWorkouts
import commands.PairActivity
import commands.SyncAthleteData
import commands.UnhideWeek
import commands.UpdateWorkouts
import kotlinx.cli.ArgParser

fun main(args: Array<String>) {
    val parser = ArgParser("coach")
    parser.subcommands(
        SyncAthleteData(),
        GenerateWorkouts(),
        UpdateWorkouts(),
        UnhideWeek(),
        DeleteEvent(),
        PairActivity(),
        AutoPair(),
        ApiGet(),
        ApiPut()
    )
    parser.parse(if (args.isEmpty()) arrayOf("--help") else args)
}
