/* SPDX-License-Identifier: Apache-2.0 */
(() => {
  "use strict";

  const search = document.querySelector("#feature-search");
  const category = document.querySelector("#category-filter");
  const status = document.querySelector("#status-filter");
  const count = document.querySelector("#result-count");
  const clear = document.querySelector("#clear-filters");
  const empty = document.querySelector("#empty-results");
  const rows = Array.from(document.querySelectorAll("#feature-rows tr"));

  if (!search || !category || !status || !count || !clear || !empty || !rows.length) return;

  const refresh = () => {
    const term = search.value.trim().toLocaleLowerCase();
    const selectedCategory = category.value;
    const selectedStatus = status.value;
    let visible = 0;

    for (const row of rows) {
      const matches =
        (!term || (row.dataset.search || "").includes(term)) &&
        (!selectedCategory || row.dataset.category === selectedCategory) &&
        (!selectedStatus || row.dataset.status === selectedStatus);
      row.hidden = !matches;
      if (matches) visible += 1;
    }

    count.textContent =
      visible === rows.length
        ? `Showing all ${rows.length} entries`
        : `Showing ${visible} of ${rows.length} entries`;
    empty.hidden = visible !== 0;
    clear.disabled = !term && !selectedCategory && !selectedStatus;
  };

  search.addEventListener("input", refresh);
  category.addEventListener("change", refresh);
  status.addEventListener("change", refresh);
  clear.addEventListener("click", () => {
    search.value = "";
    category.value = "";
    status.value = "";
    refresh();
    search.focus();
  });

  refresh();
})();
