name := "models"

version := "5.5"

organization := "edu.arizona.sista"

scalaVersion := "2.11.6"

artifactName := { (sv: ScalaVersion, module: ModuleID, artifact: Artifact) =>
  s"processors_${sv.binary}-${module.revision}-${module.name}.${artifact.extension}"
}

modelsTask <<= packagedArtifact in (Compile, packageBin) map {
  case (art: Artifact, file: File) => file
}
