DELETE FROM `role_permission`
WHERE role_id = (SELECT id FROM `role` WHERE code = 'USER')
  AND permission_id = (SELECT id FROM `permission` WHERE code = 'user:list');