# Release a catalog for a new WildFly version

## Add the new WildFly version

If the WildFly version to release is not already present in the [versions.txt](./versions.txt) file.

* Create a local branch.
* Add the new WildFly version as the last line of the `versions.txt` file.
* Git add/commit/push this change.
* Open PR against the main branch.
* Merge PR.

## Deploy the catalog

* `git checkout main`
* `git pull --rebase upstream main`
* `git tag v_<date of the day>` For example `v_20251014`
* `git push upstream v_<date of the day>` This will generate the catalog.
* `git pull --rebase upstream v_<date of the day>`
* `git push upstream main` This will start the catalog deployment.
* DONE

## Remove the latest beta release

* In case a Beta release is present in docs/index.html
* Remove the entry (for example remove: `<li><a href="39.0.0.Beta1/index.html">39.0.0.Beta1</a></li>`);
* `git add docs/index.html;git commit -m "Remove latest Beta";git push upstream main`